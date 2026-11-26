package org.ikigaidigital.repository.impl;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

interface TimeDepositJpaRepository extends JpaRepository<TimeDepositEntity, Integer> {

    /** Deposits with their withdrawals in one query; withdrawal order comes from @OrderBy on the association. */
    @Query("select distinct d from TimeDepositEntity d left join fetch d.withdrawals order by d.id")
    List<TimeDepositEntity> findAllWithWithdrawalsOrdered();

    /** SELECT ... FOR UPDATE: rows stay locked until the surrounding transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<TimeDepositEntity> findAllByOrderByIdAsc();
}
