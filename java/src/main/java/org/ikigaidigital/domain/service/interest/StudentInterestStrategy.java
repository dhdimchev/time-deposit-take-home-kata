package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.PlanType;
import org.ikigaidigital.domain.model.TimeDeposit;

/** Student plan: 3% per year, no interest after one year. */
public class StudentInterestStrategy extends PlanInterestStrategy {

    private static final int MAX_INTEREST_DAYS = 365;

    public StudentInterestStrategy() {
        super(PlanType.STUDENT);
    }

    @Override
    protected boolean qualifies(TimeDeposit deposit) {
        return deposit.getDays() <= MAX_INTEREST_DAYS;
    }
}
