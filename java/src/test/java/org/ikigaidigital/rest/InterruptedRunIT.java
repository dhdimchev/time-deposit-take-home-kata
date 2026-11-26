package org.ikigaidigital.rest;

import org.ikigaidigital.application.PostgresIntegrationTest;
import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.service.interest.InterestStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A real failure in the second batch, against PostgreSQL: the batch rolls back, the run stays open, and the next
 * POST finishes the same month for the remaining deposits only.
 */
@Import(InterruptedRunIT.ExplodingPlan.class)
class InterruptedRunIT extends PostgresIntegrationTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class ExplodingPlan {
        @Bean
        InterestStrategy explodingInterestStrategy() {
            return new InterestStrategy() {
                @Override
                public String planType() {
                    return "exploding";
                }

                @Override
                public double monthlyInterest(TimeDeposit deposit) {
                    throw new IllegalStateException("simulated failure while processing deposit " + deposit.getId());
                }
            };
        }
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private RestClient client;

    @BeforeEach
    void createClient() {
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                .defaultStatusHandler(status -> true, (request, response) -> { }) // assert on statuses ourselves
                .build();
    }

    @Test
    void failedBatchRollsBackAndTheNextUpdateFinishesTheSameMonth() {
        // With batch size 2, deposit 3 is in the second batch.
        jdbcTemplate.update("update time_deposits set plan_type = 'exploding' where id = 3");

        int failed = client.post().uri("/time-deposits/balance-updates").retrieve().toBodilessEntity()
                .getStatusCode().value();

        assertThat(failed).isGreaterThanOrEqualTo(500);
        assertThat(balances()).as("batch 1 committed, batch 2 rolled back, batch 3 never ran").containsExactly(
                "1201.00", "401.00", "240.00", "1000.00", "400.00", "240.00");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from interest_runs where completed_at is null", Integer.class)).isEqualTo(1);

        jdbcTemplate.update("update time_deposits set plan_type = 'premium' where id = 3");
        int resumed = client.post().uri("/time-deposits/balance-updates").retrieve().toBodilessEntity()
                .getStatusCode().value();

        assertThat(resumed).isEqualTo(204);
        assertThat(balances()).as("every deposit has exactly one month").containsExactly(
                "1201.00", "401.00", "241.00", "1000.00", "400.00", "240.00");
        assertThat(jdbcTemplate.queryForList("select days from time_deposits order by id", Integer.class))
                .containsExactly(75, 130, 90, 50, 430, 70);
        assertThat(jdbcTemplate.queryForObject("select count(*) from interest_runs", Integer.class))
                .as("the failed run was resumed, not replaced").isEqualTo(1);
    }

    private List<String> balances() {
        return jdbcTemplate.queryForList("select balance from time_deposits order by id", BigDecimal.class).stream()
                .map(BigDecimal::toPlainString)
                .toList();
    }
}
