package org.ikigaidigital.repository.impl;

import org.ikigaidigital.repository.api.InterestRunRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * JDBC adapter for interest runs. The partial unique index {@code interest_runs_one_open} guarantees at most one
 * open run, so concurrent callers always end up on the same run.
 */
@Repository
class JdbcInterestRunRepository implements InterestRunRepository {

    private final JdbcTemplate jdbcTemplate;

    JdbcInterestRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int startOrResumeRun() {
        while (true) {
            List<Integer> created = jdbcTemplate.queryForList("""
                    insert into interest_runs default values
                    on conflict ((completed_at is null)) where completed_at is null do nothing
                    returning id
                    """, Integer.class);
            if (!created.isEmpty()) {
                return created.get(0);
            }
            List<Integer> open = jdbcTemplate.queryForList(
                    "select id from interest_runs where completed_at is null", Integer.class);
            if (!open.isEmpty()) {
                return open.get(0);
            }
            // The open run completed between the insert and the select: try again.
        }
    }

    @Override
    public boolean completeRunIfDone(int runId) {
        jdbcTemplate.update("""
                update interest_runs set completed_at = now()
                where id = ? and completed_at is null
                  and not exists (select 1 from time_deposits where coalesce(last_interest_run_id, 0) < ?)
                """, runId, runId);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select completed_at is not null from interest_runs where id = ?", Boolean.class, runId));
    }
}
