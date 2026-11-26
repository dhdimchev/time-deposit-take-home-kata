package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.PlanType;
import org.ikigaidigital.domain.model.TimeDeposit;

/** Premium plan: 5% per year, interest starts after 45 days. */
public class PremiumInterestStrategy extends PlanInterestStrategy {

    private static final int INTEREST_FREE_DAYS = 45;

    public PremiumInterestStrategy() {
        super(PlanType.PREMIUM);
    }

    @Override
    protected boolean qualifies(TimeDeposit deposit) {
        return deposit.getDays() > INTEREST_FREE_DAYS;
    }
}
