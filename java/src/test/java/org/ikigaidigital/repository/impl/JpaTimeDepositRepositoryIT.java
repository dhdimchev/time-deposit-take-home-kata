package org.ikigaidigital.repository.impl;

import org.ikigaidigital.application.PostgresIntegrationTest;
import org.ikigaidigital.business.TimeDepositService;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class JpaTimeDepositRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private TimeDepositRepository repository;

    @Autowired
    private TimeDepositService service;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void findAllWithWithdrawalsReturnsSeededDepositsInIdOrder() {
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
                .allSatisfy(details -> assertThat(details.withdrawals()).isEmpty());
    }

    @Test
    void saveAllPersistsTwoDecimalBalancesAndDays() {
        transactionTemplate.executeWithoutResult(status -> {
            List<TimeDeposit> deposits = repository.findAllForUpdate();
            deposits.get(0).setBalance(0.1 + 0.2); // 0.30000000000000004 in double
            deposits.get(0).setDays(75);
            repository.saveAll(deposits);
        });

        BigDecimal stored = jdbcTemplate.queryForObject(
                "select balance from time_deposits where id = 1", BigDecimal.class);
        assertThat(stored).isEqualByComparingTo("0.30");
        assertThat(jdbcTemplate.queryForObject("select days from time_deposits where id = 1", Integer.class))
                .isEqualTo(75);
    }

    @Test
    void findAllForUpdateBlocksConcurrentUpdatesUntilTheLockIsReleased() throws Exception {
        CountDownLatch lockAcquired = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);

        CompletableFuture<Void> lockHolder = CompletableFuture.runAsync(() ->
                transactionTemplate.executeWithoutResult(status -> {
                    repository.findAllForUpdate();
                    lockAcquired.countDown();
                    await(releaseLock);
                }));
        assertThat(lockAcquired.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Void> update = CompletableFuture.runAsync(service::updateAllBalances);

        Thread.sleep(500);
        assertThat(update).as("update must wait for the row lock").isNotDone();

        releaseLock.countDown();
        lockHolder.get(5, TimeUnit.SECONDS);
        update.get(5, TimeUnit.SECONDS);

        BigDecimal stored = jdbcTemplate.queryForObject(
                "select balance from time_deposits where id = 1", BigDecimal.class);
        assertThat(stored).isEqualByComparingTo("1201.00");
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
