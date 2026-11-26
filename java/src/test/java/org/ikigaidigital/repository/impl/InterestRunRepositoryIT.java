package org.ikigaidigital.repository.impl;

import org.ikigaidigital.application.PostgresIntegrationTest;
import org.ikigaidigital.repository.api.InterestRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class InterestRunRepositoryIT extends PostgresIntegrationTest {

    @Autowired
    private InterestRunRepository runs;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void startOrResumeRunCreatesOneOpenRunAndReturnsItAgain() {
        int first = runs.startOrResumeRun();
        int second = runs.startOrResumeRun();

        assertThat(second).isEqualTo(first);
        assertThat(openRuns()).isEqualTo(1);
    }

    @Test
    void startOrResumeRunReturnsTheSameOpenRunForConcurrentCallers() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> callers = IntStream.range(0, 8)
                .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(start);
                    return runs.startOrResumeRun();
                }))
                .toList();
        start.countDown();

        List<Integer> ids = callers.stream().map(CompletableFuture::join).toList();

        assertThat(ids).containsOnly(ids.get(0));
        assertThat(jdbcTemplate.queryForObject("select count(*) from interest_runs", Integer.class)).isEqualTo(1);
    }

    @Test
    void completeRunIfDoneIsFalseWhileDepositsRemainAndTrueWhenAllAreInTheRun() {
        int run = runs.startOrResumeRun();

        assertThat(runs.completeRunIfDone(run)).isFalse();
        assertThat(openRuns()).isEqualTo(1);

        jdbcTemplate.update("update time_deposits set last_interest_run_id = ?", run);

        assertThat(runs.completeRunIfDone(run)).isTrue();
        assertThat(openRuns()).isZero();
        assertThat(runs.completeRunIfDone(run)).as("already completed").isTrue();
        assertThat(runs.startOrResumeRun()).as("a completed run is never resumed").isNotEqualTo(run);
    }

    private int openRuns() {
        return jdbcTemplate.queryForObject("select count(*) from interest_runs where completed_at is null", Integer.class);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
