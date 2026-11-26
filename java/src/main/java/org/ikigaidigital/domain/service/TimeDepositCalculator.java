package org.ikigaidigital.domain.service;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.service.interest.BasicInterestStrategy;
import org.ikigaidigital.domain.service.interest.InterestStrategy;
import org.ikigaidigital.domain.service.interest.PremiumInterestStrategy;
import org.ikigaidigital.domain.service.interest.StudentInterestStrategy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Applies one month of interest to time deposits. Plan-specific rules live in {@link InterestStrategy}
 * implementations, so a new plan is added by registering a strategy, without editing this class.
 */
public class TimeDepositCalculator {

    private static final int INTEREST_FREE_DAYS = 30;

    private final Map<String, InterestStrategy> strategiesByPlan;

    /** Calculator with the built-in basic, student and premium plans. */
    public TimeDepositCalculator() {
        this(List.of(new BasicInterestStrategy(), new StudentInterestStrategy(), new PremiumInterestStrategy()));
    }

    public TimeDepositCalculator(List<InterestStrategy> strategies) {
        Map<String, InterestStrategy> byPlan = new HashMap<>();
        for (InterestStrategy strategy : strategies) {
            if (byPlan.put(strategy.planType(), strategy) != null) {
                throw new IllegalArgumentException("Duplicate interest strategy for plan " + strategy.planType());
            }
        }
        this.strategiesByPlan = Map.copyOf(byPlan);
    }

    public void updateBalance(List<TimeDeposit> timeDepositList) {
        for (TimeDeposit timeDeposit : timeDepositList) {
            double roundedInterest = new BigDecimal(monthlyInterest(timeDeposit))
                    .setScale(2, RoundingMode.HALF_UP)
                    .doubleValue();
            timeDeposit.setBalance(timeDeposit.getBalance() + roundedInterest);
        }
    }

    private double monthlyInterest(TimeDeposit timeDeposit) {
        // No interest during the first 30 days, for any plan.
        if (timeDeposit.getDays() <= INTEREST_FREE_DAYS) {
            return 0;
        }
        String planType = Objects.requireNonNull(timeDeposit.getPlanType(), "planType");
        InterestStrategy strategy = strategiesByPlan.get(planType);
        // Unknown plans earn no interest.
        return strategy == null ? 0 : strategy.monthlyInterest(timeDeposit);
    }
}
