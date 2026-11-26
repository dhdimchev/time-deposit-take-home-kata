package org.ikigaidigital.business;

import java.time.Duration;

/**
 * Tuning for balance updates.
 *
 * @param batchSize     deposits processed (and locked) per transaction
 * @param rescanBackoff pause before rescanning when another worker still holds some of the run's deposits
 */
public record BalanceUpdateSettings(int batchSize, Duration rescanBackoff) {
}
