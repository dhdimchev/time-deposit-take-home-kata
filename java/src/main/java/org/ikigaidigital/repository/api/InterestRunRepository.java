package org.ikigaidigital.repository.api;

/**
 * Outbound port for tracking interest runs, which make balance updates idempotent and resumable.
 */
public interface InterestRunRepository {

    /** Returns the open run, creating it if none is open. Safe under concurrent callers: at most one run is open. */
    int startOrResumeRun();

    /** Completes the run if every deposit is in it. Returns true if the run is (now or already) completed. */
    boolean completeRunIfDone(int runId);
}
