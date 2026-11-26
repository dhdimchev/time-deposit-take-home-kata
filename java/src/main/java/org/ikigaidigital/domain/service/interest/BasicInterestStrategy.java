package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.PlanType;
import org.ikigaidigital.domain.model.TimeDeposit;

/** Basic plan: 1% per year, no plan-specific conditions. */
public class BasicInterestStrategy extends PlanInterestStrategy {

    public BasicInterestStrategy() {
        super(PlanType.BASIC);
    }

    @Override
    protected boolean qualifies(TimeDeposit deposit) {
        return true;
    }
}
