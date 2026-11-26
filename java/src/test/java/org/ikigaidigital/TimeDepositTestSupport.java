package org.ikigaidigital;

import org.ikigaidigital.domain.model.PlanType;
import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.service.TimeDepositCalculator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared fixtures for the {@link TimeDepositCalculator} characterization tests.
 * <p>
 * Expected values are never computed with the production formula. Callers pass a hand-derived
 * interest literal. The only arithmetic performed here is {@code balance + expectedInterest}
 * in {@code double}, because that addition is part of the observable contract
 * (the final balance is not rounded).
 */
final class TimeDepositTestSupport {

    private TimeDepositTestSupport() {
    }

    static TimeDeposit deposit(int id, PlanType planType, double balance, int days) {
        return deposit(id, planType.getValue(), balance, days);
    }

    static TimeDeposit deposit(PlanType planType, double balance, int days) {
        return deposit(planType.getValue(), balance, days);
    }

    static double balanceAfterOneUpdate(PlanType planType, double balance, int days) {
        return balanceAfterOneUpdate(planType.getValue(), balance, days);
    }

    static void assertInterest(PlanType planType, double balance, int days, double expectedInterest) {
        assertInterest(planType.getValue(), balance, days, expectedInterest);
    }

    // String overloads: used for plan types that are not (exact) PlanType values, e.g. "BASIC", "gold" or null.

    static TimeDeposit deposit(int id, String planType, double balance, int days) {
        return new TimeDeposit(id, planType, balance, days);
    }

    static TimeDeposit deposit(String planType, double balance, int days) {
        return deposit(1, planType, balance, days);
    }

    static double balanceAfterOneUpdate(String planType, double balance, int days) {
        TimeDeposit deposit = deposit(planType, balance, days);
        new TimeDepositCalculator().updateBalance(List.of(deposit));
        return deposit.getBalance();
    }

    static void assertInterest(String planType, double balance, int days, double expectedInterest) {
        assertThat(balanceAfterOneUpdate(planType, balance, days))
                .as("%s plan, balance %s, day %d should earn exactly %s", planType, balance, days, expectedInterest)
                .isEqualTo(balance + expectedInterest);
    }
}
