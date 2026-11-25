package org.ikigaidigital;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public class TimeDepositCalculator {

    private static final int INTEREST_FREE_DAYS = 30;
    private static final int PREMIUM_INTEREST_FREE_DAYS = 45;
    private static final int STUDENT_MAX_INTEREST_DAYS = 365;
    private static final int MONTHS_PER_YEAR = 12;

    public void updateBalance(List<TimeDeposit> timeDepositList) {
        for (TimeDeposit timeDeposit : timeDepositList) {
            double roundedInterest = new BigDecimal(monthlyInterest(timeDeposit))
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
            timeDeposit.setBalance(timeDeposit.getBalance() + roundedInterest);
        }
    }

    private double monthlyInterest(TimeDeposit timeDeposit) {
        int days = timeDeposit.getDays();
        if (days <= INTEREST_FREE_DAYS) {
            return 0;
        }

        Optional<PlanType> planType = PlanType.fromValue(timeDeposit.getPlanType());
        if (planType.isEmpty()) {
            return 0;
        }

        boolean qualifiesForInterest = switch (planType.get()) {
            case BASIC -> true;
            case STUDENT -> days <= STUDENT_MAX_INTEREST_DAYS;
            case PREMIUM -> days > PREMIUM_INTEREST_FREE_DAYS;
        };
        if (!qualifiesForInterest) {
            return 0;
        }

        return timeDeposit.getBalance() * planType.get().getAnnualInterestRate() / MONTHS_PER_YEAR;
    }
}
