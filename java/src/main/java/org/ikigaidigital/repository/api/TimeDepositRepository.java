package org.ikigaidigital.repository.api;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;

import java.util.List;

/**
 * Outbound persistence port for time deposits.
 */
public interface TimeDepositRepository {

    List<TimeDepositDetails> findAllWithWithdrawals();

    List<TimeDeposit> findNextBatchForUpdate(int runId, int afterId, int batchSize);

    void saveBatch(List<TimeDeposit> deposits, int runId);
}
