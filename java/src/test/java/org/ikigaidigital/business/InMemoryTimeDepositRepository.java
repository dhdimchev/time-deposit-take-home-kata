package org.ikigaidigital.business;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.model.TimeDepositDetails;
import org.ikigaidigital.repository.api.InterestRunRepository;
import org.ikigaidigital.repository.api.TimeDepositRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Hand-written fake of both persistence ports. Like the database, it hands out copies and only stores what
 * {@link #saveBatch} writes, so a failed batch leaves no trace. Hooks simulate a crash and a second worker.
 */
class InMemoryTimeDepositRepository implements TimeDepositRepository, InterestRunRepository {

    private final TreeMap<Integer, TimeDeposit> deposits = new TreeMap<>();
    private final Map<Integer, Integer> runOfDeposit = new HashMap<>();
    private final List<Integer> startedRuns = new ArrayList<>();
    private Integer openRun;

    private int saveBatchCalls;
    private int failOnSaveBatchCall = -1;
    private final Set<Integer> heldByOtherWorker = new HashSet<>();
    private int completeCallsBeforeRelease;

    InMemoryTimeDepositRepository add(TimeDeposit deposit) {
        deposits.put(deposit.getId(), copy(deposit));
        return this;
    }

    /** The next n-th call of saveBatch throws, as if the process crashed mid-run. */
    void failOnSaveBatch(int callNumber) {
        failOnSaveBatchCall = saveBatchCalls + callNumber;
    }

    /** Rows another worker holds locked (and never processes); released after n completeRunIfDone calls. */
    void holdRows(Set<Integer> ids, int releaseAfterCompleteCalls) {
        heldByOtherWorker.addAll(ids);
        completeCallsBeforeRelease = releaseAfterCompleteCalls;
    }

    TimeDeposit stored(int id) {
        return deposits.get(id);
    }

    Integer runOf(int id) {
        return runOfDeposit.get(id);
    }

    List<Integer> startedRuns() {
        return startedRuns;
    }

    Integer openRun() {
        return openRun;
    }

    int saveBatchCalls() {
        return saveBatchCalls;
    }

    // --- TimeDepositRepository ---

    @Override
    public List<TimeDepositDetails> findAllWithWithdrawals() {
        return deposits.values().stream().map(deposit -> new TimeDepositDetails(copy(deposit), List.of())).toList();
    }

    @Override
    public List<TimeDeposit> findNextBatchForUpdate(int runId, int afterId, int batchSize) {
        return deposits.tailMap(afterId, false).values().stream()
                .filter(deposit -> Integer.valueOf(runId).equals(openRun))
                .filter(deposit -> runOfDeposit.getOrDefault(deposit.getId(), 0) < runId)
                .filter(deposit -> !heldByOtherWorker.contains(deposit.getId()))
                .limit(batchSize)
                .map(InMemoryTimeDepositRepository::copy)
                .toList();
    }

    @Override
    public void saveBatch(List<TimeDeposit> batch, int runId) {
        saveBatchCalls++;
        if (saveBatchCalls == failOnSaveBatchCall) {
            throw new IllegalStateException("simulated crash in batch " + saveBatchCalls);
        }
        for (TimeDeposit deposit : batch) {
            deposits.put(deposit.getId(), copy(deposit));
            runOfDeposit.put(deposit.getId(), runId);
        }
    }

    // --- InterestRunRepository ---

    @Override
    public int startOrResumeRun() {
        if (openRun == null) {
            openRun = startedRuns.size() + 1;
            startedRuns.add(openRun);
        }
        return openRun;
    }

    @Override
    public boolean completeRunIfDone(int runId) {
        boolean done = !Integer.valueOf(runId).equals(openRun)
                || deposits.keySet().stream().allMatch(id -> runOfDeposit.getOrDefault(id, 0) >= runId);
        if (done && Integer.valueOf(runId).equals(openRun)) {
            openRun = null;
        }
        if (--completeCallsBeforeRelease == 0) {
            heldByOtherWorker.clear(); // the other worker rolled back: its rows are free but still unprocessed
        }
        return done;
    }

    private static TimeDeposit copy(TimeDeposit deposit) {
        return new TimeDeposit(deposit.getId(), deposit.getPlanType(), deposit.getBalance(), deposit.getDays());
    }
}
