package org.ikigaidigital.domain.service.interest;

import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class TimeDepositCalculatorExtensionTest {

    /** A plan the production code knows nothing about: 12% per year, no extra conditions. */
    private static final InterestStrategy GOLD = new InterestStrategy() {
        @Override
        public String planType() {
            return "gold";
        }

        @Override
        public double monthlyInterest(TimeDeposit deposit) {
            return deposit.getBalance() * 0.12 / 12;
        }
    };

    @Test
    void newPlanCanBeAddedWithoutEditingTheCalculator() {
        TimeDepositCalculator calculator = new TimeDepositCalculator(List.of(
                new BasicInterestStrategy(), new StudentInterestStrategy(), new PremiumInterestStrategy(), GOLD));
        TimeDeposit gold = new TimeDeposit(1, "gold", 1_200.00, 31);
        TimeDeposit basic = new TimeDeposit(2, "basic", 1_200.00, 31);

        calculator.updateBalance(List.of(gold, basic));

        assertThat(gold.getBalance()).isEqualTo(1_212.00);   // 1200 * 12% / 12 = 12.00
        assertThat(basic.getBalance()).isEqualTo(1_201.00);  // built-in plans unaffected
    }

    @Test
    void defaultCalculatorGivesUnknownPlansNoInterest() {
        TimeDeposit gold = new TimeDeposit(1, "gold", 1_200.00, 31);

        new TimeDepositCalculator().updateBalance(List.of(gold));

        assertThat(gold.getBalance()).isEqualTo(1_200.00);
    }

    @Test
    void defaultCalculatorStillThrowsForANullPlanAfter30Days() {
        assertThatNullPointerException().isThrownBy(() ->
                new TimeDepositCalculator().updateBalance(List.of(new TimeDeposit(1, null, 1_200.00, 31))));
    }

    @Test
    void duplicatePlanStrategiesAreRejected() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                new TimeDepositCalculator(List.of(new BasicInterestStrategy(), new BasicInterestStrategy())));
    }
}
