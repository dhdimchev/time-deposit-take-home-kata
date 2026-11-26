package org.ikigaidigital.repository.impl;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

interface TimeDepositJpaRepository extends JpaRepository<TimeDepositEntity, Integer> {

    /**
     * Keyset-paginated batch of deposits not yet in the run; SKIP LOCKED lets parallel workers split the work.
     * Run ids only increase, so "not yet in the run" is {@code last run < runId}: rows already stamped by a newer run
     * never qualify. Nothing qualifies once the run is completed, so a stale worker cannot pay a month twice.
     */
    @Query(value = """
            select * from time_deposits
            where id > :afterId
              and coalesce(last_interest_run_id, 0) < :runId
              and exists (select 1 from interest_runs r where r.id = :runId and r.completed_at is null)
            order by id
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<TimeDepositEntity> findNextBatchForUpdate(@Param("runId") int runId, @Param("afterId") int afterId,
                                                   @Param("batchSize") int batchSize);
}
