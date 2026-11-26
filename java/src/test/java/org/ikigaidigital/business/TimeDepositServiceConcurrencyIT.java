package org.ikigaidigital.business;

import org.ikigaidigital.application.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class TimeDepositServiceConcurrencyIT extends PostgresIntegrationTest {

    @Autowired
    private TimeDepositService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void overlappingUpdatesPayOneMonth() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        Runnable update = () -> {
            try {
                start.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            service.updateAllBalances();
        };

        CompletableFuture<Void> first = CompletableFuture.runAsync(update);
        CompletableFuture<Void> second = CompletableFuture.runAsync(update);
        start.countDown();
        CompletableFuture.allOf(first, second).get(30, TimeUnit.SECONDS);

        // Both calls joined the same run, so every deposit got exactly one month (same as a single update).
        List<BigDecimal> balances = jdbcTemplate.queryForList(
                "select balance from time_deposits order by id", BigDecimal.class);
        assertThat(balances).usingElementComparator(BigDecimal::compareTo).containsExactly(
                new BigDecimal("1201.00"), new BigDecimal("401.00"), new BigDecimal("241.00"),
                new BigDecimal("1000.00"), new BigDecimal("400.00"), new BigDecimal("240.00"));
        assertThat(jdbcTemplate.queryForList("select days from time_deposits order by id", Integer.class))
                .containsExactly(75, 130, 90, 50, 430, 70);
        // Both calls returned only after the run was completed.
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from interest_runs where completed_at is not null", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("select count(*) from interest_runs", Integer.class)).isEqualTo(1);
    }
}
