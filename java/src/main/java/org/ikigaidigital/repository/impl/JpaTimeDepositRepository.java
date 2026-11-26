package org.ikigaidigital.repository.impl;

import org.ikigaidigital.domain.model.Money;
import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.model.Withdrawal;
import org.ikigaidigital.repository.api.TimeDepositRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA adapter for the time deposit persistence port. The domain keeps balances as {@code Double};
 * the database stores {@code numeric(19,2)}.
 */
@Repository
class JpaTimeDepositRepository implements TimeDepositRepository {

    /** One query for all deposits and their withdrawals (no N+1); rows are grouped per deposit in Java. */
    private static final String ALL_WITH_WITHDRAWALS = """
            select d.id, d.plan_type, d.balance, d.days, w.id as withdrawal_id, w.amount, w.date
            from time_deposits d
            left join withdrawals w on w.time_deposit_id = d.id
            order by d.id, w.id
            """;

    private final TimeDepositJpaRepository jpaRepository;
    private final JdbcTemplate jdbcTemplate;

    JpaTimeDepositRepository(TimeDepositJpaRepository jpaRepository, JdbcTemplate jdbcTemplate) {
        this.jpaRepository = jpaRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<TimeDepositDetails> findAllWithWithdrawals() {
        DepositRowGrouper grouper = new DepositRowGrouper();
        jdbcTemplate.query(ALL_WITH_WITHDRAWALS, grouper);
        return grouper.result();
    }

    @Override
    public List<TimeDeposit> findNextBatchForUpdate(int runId, int afterId, int batchSize) {
        return jpaRepository.findNextBatchForUpdate(runId, afterId, batchSize).stream()
                .map(JpaTimeDepositRepository::toDomain)
                .toList();
    }

    @Override
    public void saveBatch(List<TimeDeposit> deposits, int runId) {
        for (TimeDeposit deposit : deposits) {
            TimeDepositEntity entity = jpaRepository.findById(deposit.getId()).orElseThrow();
            entity.setBalance(Money.toCents(deposit.getBalance()));
            entity.setDays(deposit.getDays());
            entity.setLastInterestRunId(runId);
        }
    }

    private static TimeDeposit toDomain(TimeDepositEntity entity) {
        return new TimeDeposit(entity.getId(), entity.getPlanType(), entity.getBalance().doubleValue(), entity.getDays());
    }

    /**
     * Turns the joined rows (one per withdrawal, or one with null withdrawal columns) into one
     * {@link TimeDepositDetails} per deposit. Rows arrive ordered by deposit id, so a deposit is complete as soon as
     * the next one starts.
     */
    private static final class DepositRowGrouper implements RowCallbackHandler {

        private final List<TimeDepositDetails> result = new ArrayList<>();
        private TimeDeposit current;
        private List<Withdrawal> withdrawals = new ArrayList<>();

        @Override
        public void processRow(ResultSet row) throws SQLException {
            int depositId = row.getInt("id");
            if (current == null || current.getId() != depositId) {
                finish();
                current = new TimeDeposit(depositId, row.getString("plan_type"),
                        row.getBigDecimal("balance").doubleValue(), row.getInt("days"));
            }
            int withdrawalId = row.getInt("withdrawal_id");
            if (!row.wasNull()) {
                withdrawals.add(new Withdrawal(withdrawalId, row.getBigDecimal("amount"),
                        row.getObject("date", LocalDate.class)));
            }
        }

        List<TimeDepositDetails> result() {
            finish();
            return result;
        }

        /** Adds the deposit collected so far, if any. */
        private void finish() {
            if (current != null) {
                result.add(new TimeDepositDetails(current, withdrawals));
                current = null;
                withdrawals = new ArrayList<>();
            }
        }
    }
}
