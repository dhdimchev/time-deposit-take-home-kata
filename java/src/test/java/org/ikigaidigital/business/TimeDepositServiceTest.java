package org.ikigaidigital.business;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.service.DepositAgeing;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Duration;
import java.util.Set;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class TimeDepositServiceTest {

    private final InMemoryTimeDepositRepository repository = new InMemoryTimeDepositRepository();
    private final TimeDepositService service = new TimeDepositService(repository, repository,
            new TimeDepositCalculator(), new DepositAgeing(), TransactionOperations.withoutTransaction(),
            new BalanceUpdateSettings(2, Duration.ZERO));

    @Test
    void updateAllBalancesAppliesInterestWithTheCurrentAgeThenAgesEveryDeposit() {
        repository
                .add(new TimeDeposit(1, "basic", 1_200.00, 45))   // 1200 * 1% / 12 = 1.00
                .add(new TimeDeposit(2, "premium", 240.00, 40))   // premium earns from day 46
                .add(new TimeDeposit(3, "student", 400.00, 100)); // 400 * 3% / 12 = 1.00

        service.updateAllBalances();

        assertBalanceAndDays(1, 1_201.00, 75);
        assertBalanceAndDays(2, 240.00, 70);
        assertBalanceAndDays(3, 401.00, 130);
    }

    @Test
    void updateAllBalancesProcessesEveryDepositOnceAcrossBatches() {
        addBasicDeposits(5);

        service.updateAllBalances();

        IntStream.rangeClosed(1, 5).forEach(id -> assertBalanceAndDays(id, 1_201.00, 75));
        assertThat(repository.saveBatchCalls()).as("batches of 2, 2 and 1").isEqualTo(3);
        assertThat(repository.openRun()).as("run completed").isNull();
    }

    @Test
    void failureMidRunLeavesTheRunOpenForResume() {
        addBasicDeposits(5);
        repository.failOnSaveBatch(2);

        assertThatIllegalStateException().isThrownBy(service::updateAllBalances);

        assertBalanceAndDays(1, 1_201.00, 75);
        assertBalanceAndDays(2, 1_201.00, 75);
        IntStream.rangeClosed(3, 5).forEach(id -> assertBalanceAndDays(id, 1_200.00, 45));
        assertThat(repository.openRun()).as("run still open").isNotNull();

        service.updateAllBalances();

        IntStream.rangeClosed(1, 5).forEach(id -> assertBalanceAndDays(id, 1_201.00, 75));
        assertThat(repository.startedRuns()).as("the retry resumed the same run").hasSize(1);
        assertThat(repository.openRun()).isNull();
    }

    @Test
    void rescansUntilTheRunIsComplete() {
        addBasicDeposits(5);
        repository.holdRows(Set.of(3, 4), 1); // another worker holds 3 and 4, then rolls back

        service.updateAllBalances();

        IntStream.rangeClosed(1, 5).forEach(id -> assertBalanceAndDays(id, 1_201.00, 75));
        assertThat(repository.openRun()).isNull();
    }

    @Test
    void completedRunIsFollowedByANewRun() {
        addBasicDeposits(2);

        service.updateAllBalances();
        service.updateAllBalances();

        assertBalanceAndDays(1, 1_202.00, 105); // 1201 / 1200 = 1.0008 -> 1.00
        assertThat(repository.startedRuns()).containsExactly(1, 2);
    }

    @Test
    void getAllReturnsEveryDepositInIdOrder() {
        addBasicDeposits(3);

        assertThat(service.getAll()).extracting(details -> details.deposit().getId()).containsExactly(1, 2, 3);
    }

    private void addBasicDeposits(int count) {
        IntStream.rangeClosed(1, count).forEach(id -> repository.add(new TimeDeposit(id, "basic", 1_200.00, 45)));
    }

    private void assertBalanceAndDays(int id, double balance, int days) {
        assertThat(repository.stored(id).getBalance()).as("balance of %d", id).isEqualTo(balance);
        assertThat(repository.stored(id).getDays()).as("days of %d", id).isEqualTo(days);
    }
}
