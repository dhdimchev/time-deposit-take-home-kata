package org.ikigaidigital.domain.service;

import org.ikigaidigital.domain.model.TimeDeposit;

import java.util.List;

/**
 * Ages time deposits by one month. Each balance update represents one month passing.
 */
public class DepositAgeing {

    /**
     * Length of a month in days. Assumption: the standard 30/360 day-count convention (every month counts as 30 days),
     * which matches the brief's "first 30 days" rule and keeps {@code days} an integer. The brief's tables store no
     * dates, so calendar months are not possible.
     */
    public static final int DAYS_PER_MONTH = 30;

    public void advanceOneMonth(List<TimeDeposit> deposits) {
        // One month has passed: advance every deposit by 30 days, following the standard 30/360 convention.
        deposits.forEach(deposit -> deposit.setDays(deposit.getDays() + DAYS_PER_MONTH));
    }
}
