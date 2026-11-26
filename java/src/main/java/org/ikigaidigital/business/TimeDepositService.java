package org.ikigaidigital.business;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.domain.service.DepositAgeing;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.ikigaidigital.repository.api.InterestRunRepository;
import org.ikigaidigital.repository.api.TimeDepositRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;

/**
 * Use cases for time deposits: list them, and apply one month of interest to all of them.
 */
@Service
public class TimeDepositService {

    private final TimeDepositRepository repository;
    private final InterestRunRepository interestRuns;
    private final TimeDepositCalculator calculator;
    private final DepositAgeing depositAgeing;
    private final TransactionOperations transactions;
    private final BalanceUpdateSettings settings;

    public TimeDepositService(TimeDepositRepository repository, InterestRunRepository interestRuns,
                              TimeDepositCalculator calculator, DepositAgeing depositAgeing,
                              TransactionOperations transactions, BalanceUpdateSettings settings) {
        this.repository = repository;
        this.interestRuns = interestRuns;
        this.calculator = calculator;
        this.depositAgeing = depositAgeing;
        this.transactions = transactions;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public List<TimeDepositDetails> getAll() {
        return repository.findAllWithWithdrawals();
    }

    /**
     * Applies one month to every deposit as an interest run, processed in batches with one short transaction each.
     * <ul>
     *   <li>Resumable: if a previous call failed part-way, its run is still open and this call finishes it, so
     *       deposits already processed are not paid twice.</li>
     *   <li>Overlapping calls join the same open run and split the batches; the month is applied once.</li>
     *   <li>Returns only when the run is complete. If another worker still holds some deposits, this call waits
     *       briefly and rescans, which also picks up rows whose worker rolled back.</li>
     * </ul>
     */
    public void updateAllBalances() {
        int runId = interestRuns.startOrResumeRun();
        do {
            processRemainingBatches(runId);
        } while (!interestRuns.completeRunIfDone(runId) && pauseBeforeRescan());
    }

    private void processRemainingBatches(int runId) {
        int afterId = 0;
        Integer lastId;
        while ((lastId = processNextBatch(runId, afterId)) != null) {
            afterId = lastId;
        }
    }

    /** Processes one batch in its own transaction; returns the last deposit id, or null when nothing is left. */
    private Integer processNextBatch(int runId, int afterId) {
        return transactions.execute(status -> {
            List<TimeDeposit> batch = repository.findNextBatchForUpdate(runId, afterId, settings.batchSize());
            if (batch.isEmpty()) {
                return null;
            }
            // Interest is calculated with the age before this update, then every deposit ages by one month.
            calculator.updateBalance(batch);
            depositAgeing.advanceOneMonth(batch);
            repository.saveBatch(batch, runId);
            return batch.get(batch.size() - 1).getId();
        });
    }

    private boolean pauseBeforeRescan() {
        try {
            Thread.sleep(settings.rescanBackoff().toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the interest run to complete", e);
        }
    }
}
