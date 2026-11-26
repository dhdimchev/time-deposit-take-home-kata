package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PremiumInterestStrategyTest {

    private final PremiumInterestStrategy strategy = new PremiumInterestStrategy();

    @Test
    void appliesToThePremiumPlan() {
        assertThat(strategy.planType()).isEqualTo("premium");
    }

    @Test
    void earnsNothingUpToAndIncludingDay45() {
        assertThat(strategy.monthlyInterest(new TimeDeposit(1, "premium", 240.00, 45))).isEqualTo(0.0);
    }

    @Test
    void earnsFivePercentPerYearFromDay46() {
        // The evaluation order balance * rate / 12 is part of the contract (spec 001).
        assertThat(strategy.monthlyInterest(new TimeDeposit(1, "premium", 13.20, 46))).isEqualTo(13.20 * 0.05 / 12);
    }
}
