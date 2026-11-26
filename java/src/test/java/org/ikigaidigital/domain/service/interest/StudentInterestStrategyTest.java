package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StudentInterestStrategyTest {

    private final StudentInterestStrategy strategy = new StudentInterestStrategy();

    @Test
    void appliesToTheStudentPlan() {
        assertThat(strategy.planType()).isEqualTo("student");
    }

    @Test
    void earnsThreePercentPerYearUpToAndIncludingDay365() {
        // The evaluation order balance * rate / 12 is part of the contract (spec 001).
        assertThat(strategy.monthlyInterest(new TimeDeposit(1, "student", 22.00, 365))).isEqualTo(22.00 * 0.03 / 12);
    }

    @Test
    void earnsNothingFromDay366() {
        assertThat(strategy.monthlyInterest(new TimeDeposit(1, "student", 400.00, 366))).isEqualTo(0.0);
    }
}
