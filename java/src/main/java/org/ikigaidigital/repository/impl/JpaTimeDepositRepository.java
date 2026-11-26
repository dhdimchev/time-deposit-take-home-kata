package org.ikigaidigital.repository.impl;

import org.ikigaidigital.domain.model.Money;
import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.model.Withdrawal;
import org.ikigaidigital.repository.api.TimeDepositRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * JPA adapter for the time deposit persistence port. The domain keeps balances as {@code Double};
 * the database stores {@code numeric(19,2)}.
 */
@Repository
class JpaTimeDepositRepository implements TimeDepositRepository {

    private final TimeDepositJpaRepository jpaRepository;

    JpaTimeDepositRepository(TimeDepositJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<TimeDepositDetails> findAllWithWithdrawals() {
        return jpaRepository.findAllWithWithdrawalsOrdered().stream()
                .map(entity -> new TimeDepositDetails(toDomain(entity), toWithdrawals(entity)))
                .toList();
    }

    @Override
    public List<TimeDeposit> findAllForUpdate() {
        return jpaRepository.findAllByOrderByIdAsc().stream()
                .map(JpaTimeDepositRepository::toDomain)
                .toList();
    }

    @Override
    public void saveAll(List<TimeDeposit> deposits) {
        Map<Integer, TimeDepositEntity> entities = jpaRepository
                .findAllById(deposits.stream().map(TimeDeposit::getId).toList()).stream()
                .collect(Collectors.toMap(TimeDepositEntity::getId, Function.identity()));
        for (TimeDeposit deposit : deposits) {
            TimeDepositEntity entity = entities.get(deposit.getId());
            entity.setBalance(Money.toCents(deposit.getBalance()));
            entity.setDays(deposit.getDays());
        }
    }

    private static TimeDeposit toDomain(TimeDepositEntity entity) {
        return new TimeDeposit(entity.getId(), entity.getPlanType(), entity.getBalance().doubleValue(), entity.getDays());
    }

    private static List<Withdrawal> toWithdrawals(TimeDepositEntity entity) {
        return entity.getWithdrawals().stream()
                .map(withdrawal -> new Withdrawal(withdrawal.getId(), withdrawal.getAmount(), withdrawal.getDate()))
                .toList();
    }
}
