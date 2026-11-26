package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BasicInterestStrategyTest {

    private final BasicInterestStrategy strategy = new BasicInterestStrategy();

    @Test
    void appliesToTheBasicPlan() {
        assertThat(strategy.planType()).isEqualTo("basic");
    }

    @ParameterizedTest(name = "day {0} earns interest")
    @ValueSource(ints = {31, 365, 366, Integer.MAX_VALUE})
    void basicAlwaysEarnsOneTwelfthOfOnePercent(int days) {
        // The evaluation order balance * rate / 12 is part of the contract (spec 001): other orders round differently.
        assertThat(strategy.monthlyInterest(new TimeDeposit(1, "basic", 1_234.56, days)))
                .isEqualTo(1_234.56 * 0.01 / 12);
    }
}
