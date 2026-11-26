package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.PlanType;
import org.ikigaidigital.domain.model.TimeDeposit;

/**
 * Base for the built-in plans: a fixed annual rate from {@link PlanType}, applied monthly when the plan's
 * condition holds.
 */
abstract class PlanInterestStrategy implements InterestStrategy {

    private static final int MONTHS_PER_YEAR = 12;

    private final PlanType plan;

    PlanInterestStrategy(PlanType plan) {
        this.plan = plan;
    }

    @Override
    public final String planType() {
        return plan.getValue();
    }

    @Override
    public final double monthlyInterest(TimeDeposit deposit) {
        if (!qualifies(deposit)) {
            return 0;
        }
        return deposit.getBalance() * plan.getAnnualInterestRate() / MONTHS_PER_YEAR;
    }

    protected abstract boolean qualifies(TimeDeposit deposit);
}
