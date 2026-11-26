package org.ikigaidigital.repository.impl;

import org.ikigaidigital.application.PostgresIntegrationTest;
import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.model.Withdrawal;
import org.ikigaidigital.repository.api.TimeDepositRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JpaTimeDepositRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private TimeDepositRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findAllWithWithdrawalsGroupsRowsPerDepositInIdOrder() {
        List<TimeDepositDetails> deposits = repository.findAllWithWithdrawals();

        assertThat(deposits).extracting(details -> details.deposit().getId()).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(deposits).extracting(details -> details.deposit().getPlanType())
                .containsExactly("basic", "student", "premium", "basic", "student", "premium");
        assertThat(deposits).extracting(details -> details.deposit().getDays())
                .containsExactly(45, 100, 60, 20, 400, 40);
        assertThat(deposits).extracting(details -> details.deposit().getBalance())
                .containsExactly(1_200.00, 400.00, 240.00, 1_000.00, 400.00, 240.00);

        assertThat(deposits.get(1).withdrawals()).containsExactly(
                new Withdrawal(1, new BigDecimal("50.00"), LocalDate.of(2026, 1, 15)));
        assertThat(deposits.get(2).withdrawals()).containsExactly(
                new Withdrawal(2, new BigDecimal("100.00"), LocalDate.of(2026, 2, 1)),
                new Withdrawal(3, new BigDecimal("25.50"), LocalDate.of(2026, 3, 10)));
        assertThat(List.of(deposits.get(0), deposits.get(3), deposits.get(4), deposits.get(5)))
                .as("deposits without withdrawals, including the last one in the result, are still emitted")
                .allSatisfy(details -> assertThat(details.withdrawals()).isEmpty());
    }

    @Test
    void findAllWithWithdrawalsIncludesTheLastDepositWithItsWithdrawals() {
        jdbcTemplate.update("insert into time_deposits (id, plan_type, days, balance) values (7, 'basic', 31, 10.00)");
        jdbcTemplate.update("insert into withdrawals (id, time_deposit_id, amount, date) values "
                + "(10, 7, 1.00, '2026-04-01'), (11, 7, 2.00, '2026-04-02')");

        List<TimeDepositDetails> deposits = repository.findAllWithWithdrawals();

        assertThat(deposits).extracting(details -> details.deposit().getId()).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(deposits.get(6).withdrawals()).extracting(Withdrawal::id).containsExactly(10, 11);
    }

    @Test
    void findNextBatchForUpdateReturnsDepositsAfterTheCursorNotYetInTheRun() {
        int run = jdbcTemplate.queryForObject("insert into interest_runs default values returning id", Integer.class);

        assertThat(batchIds(run, 0)).containsExactly(1, 2);
        assertThat(batchIds(run, 2)).containsExactly(3, 4);

        transactionTemplate.executeWithoutResult(status ->
                repository.saveBatch(repository.findNextBatchForUpdate(run, 0, 2), run));

        assertThat(batchIds(run, 0)).as("deposits already in the run are skipped").containsExactly(3, 4);
    }

    @Test
    void findNextBatchForUpdateSkipsRowsLockedByAnotherTransaction() throws Exception {
        int run = jdbcTemplate.queryForObject("insert into interest_runs default values returning id", Integer.class);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<Void> otherWorker = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    repository.findNextBatchForUpdate(run, 0, 2); // locks ids 1 and 2
                    locked.countDown();
                    await(release);
                }));
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

        try {
            assertThat(batchIds(run, 0)).containsExactly(3, 4);
        } finally {
            release.countDown();
            otherWorker.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void findNextBatchForUpdateReturnsNothingForACompletedRunEvenAfterANewerRunStampedRows() {
        // Run 1 completed (every deposit stamped), run 2 is in progress and has stamped deposits 1 and 2.
        int completed = jdbcTemplate.queryForObject(
                "insert into interest_runs (completed_at) values (now()) returning id", Integer.class);
        jdbcTemplate.update("update time_deposits set last_interest_run_id = ?", completed);
        int newer = jdbcTemplate.queryForObject("insert into interest_runs default values returning id", Integer.class);
        jdbcTemplate.update("update time_deposits set last_interest_run_id = ? where id in (1, 2)", newer);

        // A stale worker that still holds the completed run id must not pay anything again.
        assertThat(batchIds(completed, 0)).isEmpty();
        assertThat(batchIds(newer, 0)).containsExactly(3, 4);
    }

    @Test
    void saveBatchPersistsBalanceDaysAndRun() {
        int run = jdbcTemplate.queryForObject("insert into interest_runs default values returning id", Integer.class);

        transactionTemplate.executeWithoutResult(status -> {
            List<TimeDeposit> batch = repository.findNextBatchForUpdate(run, 0, 1);
            batch.get(0).setBalance(0.1 + 0.2); // 0.30000000000000004 in double
            batch.get(0).setDays(75);
            repository.saveBatch(batch, run);
        });

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select balance, days, last_interest_run_id from time_deposits where id = 1");
        assertThat((BigDecimal) row.get("balance")).isEqualByComparingTo("0.30");
        assertThat(row.get("days")).isEqualTo(75);
        assertThat(row.get("last_interest_run_id")).isEqualTo(run);
    }

    private List<Integer> batchIds(int run, int afterId) {
        return transactionTemplate.execute(status -> repository.findNextBatchForUpdate(run, afterId, 2).stream()
                .map(TimeDeposit::getId)
                .toList());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
