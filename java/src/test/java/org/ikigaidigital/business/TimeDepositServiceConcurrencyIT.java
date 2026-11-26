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
    void parallelUpdatesApplyTwoMonths() throws Exception {
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

        // Same result as two sequential updates. The first update ages deposits by 30 days, so on the second one
        // deposit 4 (basic, day 50) earns 1000/1200 -> 0.83 and deposit 6 (premium, day 70) earns 1.00.
        List<BigDecimal> balances = jdbcTemplate.queryForList(
                "select balance from time_deposits order by id", BigDecimal.class);
        assertThat(balances).usingElementComparator(BigDecimal::compareTo).containsExactly(
                new BigDecimal("1202.00"), new BigDecimal("402.00"), new BigDecimal("242.00"),
                new BigDecimal("1000.83"), new BigDecimal("400.00"), new BigDecimal("241.00"));
        assertThat(jdbcTemplate.queryForList("select days from time_deposits order by id", Integer.class))
                .containsExactly(105, 160, 120, 80, 460, 100);
    }
}
