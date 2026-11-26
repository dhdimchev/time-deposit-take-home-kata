package org.ikigaidigital.rest;

import org.ikigaidigital.application.PostgresIntegrationTest;
import org.ikigaidigital.rest.dto.TimeDepositDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimeDepositApiIT extends PostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private RestClient client;

    @BeforeEach
    void createClient() {
        client = RestClient.create("http://localhost:" + port);
    }

    @Test
    void getReturnsAllSeededDepositsWithWithdrawals() {
        TimeDepositDto[] deposits = getAll();

        assertThat(deposits).extracting(TimeDepositDto::getId).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(deposits).extracting(TimeDepositDto::getPlanType)
                .containsExactly("basic", "student", "premium", "basic", "student", "premium");
        assertThat(deposits).extracting(TimeDepositDto::getDays).containsExactly(45, 100, 60, 20, 400, 40);
        assertBalances(deposits, "1200.00", "400.00", "240.00", "1000.00", "400.00", "240.00");
        assertThat(deposits).extracting(deposit -> deposit.getWithdrawals().size())
                .containsExactly(0, 1, 2, 0, 0, 0);
    }

    @Test
    void getSerializesWithdrawalDatesAsIsoDates() {
        String body = client.get().uri("/time-deposits").retrieve().body(String.class);

        assertThat(body).contains("\"date\":\"2026-01-15\"");
    }

    @Test
    void postUpdatesAllBalancesAndReturnsNoContent() {
        ResponseEntity<String> response = client.post().uri("/time-deposits/balance-updates")
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        assertThat(response.getBody()).isNull();
        TimeDepositDto[] updated = getAll();
        assertBalances(updated, "1201.00", "401.00", "241.00", "1000.00", "400.00", "240.00");
        assertThat(updated).extracting(TimeDepositDto::getDays).containsExactly(75, 130, 90, 50, 430, 70);
    }

    @Test
    void secondUpdateUsesTheAgedDeposits() {
        updateAll();
        updateAll();

        // Deposit 4 (basic) reached day 50 and deposit 6 (premium) day 70 after the first update,
        // so both earn interest on the second: 1000/1200 -> 0.83 and 240 * 5% / 12 -> 1.00.
        TimeDepositDto[] deposits = getAll();
        assertBalances(deposits, "1202.00", "402.00", "242.00", "1000.83", "400.00", "241.00");
        assertThat(deposits).extracting(TimeDepositDto::getDays).containsExactly(105, 160, 120, 80, 460, 100);
    }

    @Test
    void emptyDatabaseReturnsEmptyListAndUpdateSucceeds() {
        jdbcTemplate.update("delete from withdrawals");
        jdbcTemplate.update("delete from time_deposits");

        assertThat(getAll()).isEmpty();
        assertThat(client.post().uri("/time-deposits/balance-updates").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(204);
    }

    @Test
    void unknownPlanTypeIsListedAndEarnsNoInterest() {
        jdbcTemplate.update(
                "insert into time_deposits (id, plan_type, days, balance) values (100, 'gold', 100, 500.00)");

        client.post().uri("/time-deposits/balance-updates").retrieve().toBodilessEntity();

        assertThat(getAll()).filteredOn(deposit -> deposit.getId() == 100).singleElement().satisfies(deposit -> {
            assertThat(deposit.getPlanType()).isEqualTo("gold");
            assertThat(deposit.getBalance()).isEqualByComparingTo("500.00");
        });
    }

    @Test
    void applicationExposesExactlyTwoApiOperations() {
        Map<RequestMappingInfo, HandlerMethod> ownMappings = handlerMapping.getHandlerMethods().entrySet().stream()
                .filter(entry -> entry.getValue().getBeanType().getPackageName().startsWith("org.ikigaidigital"))
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(ownMappings.keySet()).extracting(RequestMappingInfo::toString).containsExactlyInAnyOrder(
                "{GET [/time-deposits], produces [application/json]}",
                "{POST [/time-deposits/balance-updates]}");
    }

    private void updateAll() {
        assertThat(client.post().uri("/time-deposits/balance-updates").retrieve().toBodilessEntity()
                .getStatusCode().value()).isEqualTo(204);
    }

    private TimeDepositDto[] getAll() {
        return client.get().uri("/time-deposits").retrieve().body(TimeDepositDto[].class);
    }

    private static void assertBalances(TimeDepositDto[] deposits, String... expected) {
        assertThat(deposits).extracting(TimeDepositDto::getBalance)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(java.util.Arrays.stream(expected).map(BigDecimal::new).toArray(BigDecimal[]::new));
    }
}
