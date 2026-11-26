package org.ikigaidigital.business;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.model.Withdrawal;
import org.ikigaidigital.domain.service.DepositAgeing;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeDepositServiceTest {

    private final InMemoryTimeDepositRepository repository = new InMemoryTimeDepositRepository();
    private final TimeDepositService service = new TimeDepositService(repository, new TimeDepositCalculator(), new DepositAgeing());

    @Test
    void updateAllBalancesSavesTheCalculatedBalancesOfAllDeposits() {
        repository
                .add(details(new TimeDeposit(1, "basic", 1_200.00, 45)))    // 1200 * 1% / 12 = 1.00
                .add(details(new TimeDeposit(2, "premium", 240.00, 40)))    // premium earns from day 46
                .add(details(new TimeDeposit(3, "student", 400.00, 100)));  // 400 * 3% / 12 = 1.00

        service.updateAllBalances();

        assertThat(repository.savedBalances()).containsExactly(List.of(1_201.00, 240.00, 401.00));
    }

    @Test
    void updateAllBalancesAgesEveryDepositByThirtyDaysAfterCalculatingInterest() {
        repository
                .add(details(new TimeDeposit(1, "premium", 240.00, 40)))  // day 40: no premium interest yet
                .add(details(new TimeDeposit(2, "basic", 1_200.00, 45))); // day 45: basic earns 1.00

        service.updateAllBalances();

        // Interest uses the age before the update; the age then advances by one 30-day month (30/360).
        assertThat(repository.savedBalances()).containsExactly(List.of(240.00, 1_201.00));
        assertThat(repository.savedDays()).containsExactly(List.of(70, 75));
    }

    @Test
    void updateAllBalancesReadsDepositsThroughTheLockingQuery() {
        repository.add(details(new TimeDeposit(1, "basic", 1_200.00, 45)));

        service.updateAllBalances();

        assertThat(repository.findAllForUpdateCalls()).isEqualTo(1);
    }

    @Test
    void getAllReturnsTheDetailsFromTheRepository() {
        repository.add(new TimeDepositDetails(
                new TimeDeposit(3, "premium", 240.00, 60),
                List.of(new Withdrawal(2, new BigDecimal("100.00"), LocalDate.of(2026, 2, 1)),
                        new Withdrawal(3, new BigDecimal("25.50"), LocalDate.of(2026, 3, 10)))));

        assertThat(service.getAll()).isEqualTo(repository.findAllWithWithdrawals());
    }

    private static TimeDepositDetails details(TimeDeposit deposit) {
        return new TimeDepositDetails(deposit, List.of());
    }
}
