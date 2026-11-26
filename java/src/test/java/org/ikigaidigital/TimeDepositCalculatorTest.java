package org.ikigaidigital;

import org.ikigaidigital.domain.model.PlanType;
import org.ikigaidigital.domain.model.TimeDeposit;
import org.ikigaidigital.domain.service.TimeDepositCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.ikigaidigital.domain.model.PlanType.*;
import static org.ikigaidigital.TimeDepositTestSupport.*;

/**
 * Characterization tests for the business rules of {@link TimeDepositCalculator#updateBalance(List)}.
 * <p>
 * Balances are chosen so each plan's monthly interest is exactly 1.00 (basic 1200, student 400,
 * premium 240). Every eligibility boundary is therefore checked on both sides: the eligible side
 * earns exactly 1.00 and the ineligible side earns exactly 0.00.
 * <p>
 * Numerical precision, overflow and underflow behaviour is characterized separately in
 * {@link TimeDepositCalculatorNumericalCharacterizationTest}.
 */
public class TimeDepositCalculatorTest {

    private static final double BASIC_BALANCE_EARNING_ONE = 1200.00;   // 1200 * 1% / 12 = 1.00
    private static final double STUDENT_BALANCE_EARNING_ONE = 400.00;  //  400 * 3% / 12 = 1.00
    private static final double PREMIUM_BALANCE_EARNING_ONE = 240.00;  //  240 * 5% / 12 = 1.00

    private final TimeDepositCalculator calculator = new TimeDepositCalculator();

    // Original starter test, preserved unchanged.
    @Test
    public void updateBalance_Test() {
        TimeDepositCalculator calc = new TimeDepositCalculator();
        List<TimeDeposit> plans = Arrays.asList(
            new TimeDeposit(1,"basic", 1234567.00, 45)
        );
        calc.updateBalance(plans);

        assertThat(1).isEqualTo(1);
    }

    @Test
    void starterScenario_basicDepositAtDay45_earnsOneMonthOfBasicInterest() {
        // 1,234,567.00 * 1% / 12 = 1028.80583... -> 1028.81
        assertThat(balanceAfterOneUpdate(BASIC, 1_234_567.00, 45)).isEqualTo(1_235_595.81);
    }

    @Nested
    @DisplayName("Eligibility by plan and deposit age in days")
    class EligibilityBoundaries {

        @ParameterizedTest(name = "BASIC, day {0} -> interest {1}")
        @CsvSource({
                "0, 0.00", "29, 0.00", "30, 0.00",
                "31, 1.00", "32, 1.00", "365, 1.00", "366, 1.00", "2147483647, 1.00"
        })
        void basicEarnsInterestFromDay31WithoutUpperLimit(int days, double expectedInterest) {
            assertInterest(BASIC, BASIC_BALANCE_EARNING_ONE, days, expectedInterest);
        }

        @ParameterizedTest(name = "STUDENT, day {0} -> interest {1}")
        @CsvSource({
                "0, 0.00", "29, 0.00", "30, 0.00",
                "31, 1.00", "32, 1.00", "364, 1.00", "365, 1.00",
                "366, 0.00", "367, 0.00", "2147483647, 0.00"
        })
        void studentEarnsInterestFromDay31UpToAndIncludingDay365(int days, double expectedInterest) {
            assertInterest(STUDENT, STUDENT_BALANCE_EARNING_ONE, days, expectedInterest);
        }

        @ParameterizedTest(name = "PREMIUM, day {0} -> interest {1}")
        @CsvSource({
                "0, 0.00", "29, 0.00", "30, 0.00", "31, 0.00", "44, 0.00", "45, 0.00",
                "46, 1.00", "47, 1.00", "365, 1.00", "366, 1.00", "2147483647, 1.00"
        })
        void premiumEarnsInterestFromDay46WithoutUpperLimit(int days, double expectedInterest) {
            assertInterest(PREMIUM, PREMIUM_BALANCE_EARNING_ONE, days, expectedInterest);
        }

        @ParameterizedTest(name = "{0} at day 30 earns nothing")
        @EnumSource(PlanType.class)
        void noPlanEarnsInterestDuringTheFirst30Days(PlanType planType) {
            assertInterest(planType, 12_000.00, 30, 0.00);
        }
    }

    @Nested
    @DisplayName("Monthly interest amount")
    class MonthlyInterest {

        @ParameterizedTest(name = "{0}: 12,000.00 earns {1} per month")
        @CsvSource({
                "BASIC,   10.00",  // 1% / 12
                "STUDENT, 30.00",  // 3% / 12
                "PREMIUM, 50.00"   // 5% / 12
        })
        void annualRateIsAppliedAsOneTwelfthPerMonth(PlanType planType, double expectedInterest) {
            assertInterest(planType, 12_000.00, 100, expectedInterest);
        }

        @Test
        void premiumEarnsMoreThanStudentWhichEarnsMoreThanBasic() {
            double basic = balanceAfterOneUpdate(BASIC, 12_000.00, 100);
            double student = balanceAfterOneUpdate(STUDENT, 12_000.00, 100);
            double premium = balanceAfterOneUpdate(PREMIUM, 12_000.00, 100);

            assertThat(basic).isLessThan(student);
            assertThat(student).isLessThan(premium);
        }

        @Test
        void interestIsProportionalToTheCurrentBalance() {
            assertInterest(BASIC, 1_200.00, 31, 1.00);
            assertInterest(BASIC, 2_400.00, 31, 2.00);
            assertInterest(BASIC, 120_000.00, 31, 100.00);
        }

        @ParameterizedTest(name = "BASIC, day {0} -> exactly one month of interest")
        @ValueSource(ints = {31, 100, 365, 3_650, 10_000})
        void interestIsNotProratedOrAccumulatedByDepositAge(int days) {
            assertInterest(BASIC, BASIC_BALANCE_EARNING_ONE, days, 1.00);
        }

        @Test
        void olderDepositsReceiveNoRetroactiveInterestForPastMonths() {
            // a premium deposit that is more than a year old still gets one month only
            assertInterest(PREMIUM, PREMIUM_BALANCE_EARNING_ONE, 400, 1.00);
        }
    }

    @Nested
    @DisplayName("Repeated invocation")
    class RepeatedInvocation {

        @Test
        void eachInvocationAppliesExactlyOneMonthOfInterest() {
            TimeDeposit deposit = deposit(BASIC, 1_200.00, 31);

            calculator.updateBalance(List.of(deposit));
            assertThat(deposit.getBalance()).isEqualTo(1_201.00);

            calculator.updateBalance(List.of(deposit));
            assertThat(deposit.getBalance()).isEqualTo(1_202.00);
        }

        @Test
        void subsequentInvocationsCompoundOnTheUpdatedBalance() {
            TimeDeposit deposit = deposit(BASIC, 12_000.00, 31);

            calculator.updateBalance(List.of(deposit)); // 12,000.00 / 1200 = 10.00
            assertThat(deposit.getBalance()).isEqualTo(12_010.00);

            calculator.updateBalance(List.of(deposit)); // 12,010.00 / 1200 = 10.00833 -> 10.01
            assertThat(deposit.getBalance()).isEqualTo(12_020.01);

            calculator.updateBalance(List.of(deposit)); // 12,020.01 / 1200 = 10.016675 -> 10.02
            assertThat(deposit.getBalance()).isEqualTo(12_030.03);
        }

        @Test
        void ineligibleDepositStaysUnchangedAcrossInvocations() {
            TimeDeposit deposit = deposit(BASIC, 1_200.00, 30);

            for (int i = 0; i < 5; i++) {
                calculator.updateBalance(List.of(deposit));
            }

            assertThat(deposit.getBalance()).isEqualTo(1_200.00);
        }

        @Test
        void invocationsDoNotAdvanceTheDepositAge() {
            TimeDeposit waiting = deposit(BASIC, 1_200.00, 30);
            TimeDeposit earning = deposit(PREMIUM, 240.00, 46);

            for (int i = 0; i < 3; i++) {
                calculator.updateBalance(List.of(waiting, earning));
            }

            assertThat(waiting.getDays()).isEqualTo(30);
            assertThat(waiting.getBalance()).isEqualTo(1_200.00);
            assertThat(earning.getDays()).isEqualTo(46);
        }
    }

    @Nested
    @DisplayName("Collection processing")
    class CollectionProcessing {

        @Test
        void emptyListIsAccepted() {
            List<TimeDeposit> deposits = new ArrayList<>();

            calculator.updateBalance(deposits);

            assertThat(deposits).isEmpty();
        }

        @Test
        void singleDepositIsUpdated() {
            TimeDeposit deposit = deposit(STUDENT, 400.00, 31);

            calculator.updateBalance(new ArrayList<>(List.of(deposit)));

            assertThat(deposit.getBalance()).isEqualTo(401.00);
        }

        @Test
        void depositsOfTheSamePlanAreEachUpdatedFromTheirOwnBalance() {
            TimeDeposit small = deposit(1, BASIC, 1_200.00, 31);
            TimeDeposit medium = deposit(2, BASIC, 2_400.00, 31);
            TimeDeposit large = deposit(3, BASIC, 12_000.00, 31);

            calculator.updateBalance(Arrays.asList(small, medium, large));

            assertThat(small.getBalance()).isEqualTo(1_201.00);
            assertThat(medium.getBalance()).isEqualTo(2_402.00);
            assertThat(large.getBalance()).isEqualTo(12_010.00);
        }

        @Test
        void mixedPlansAndAgesAreEachUpdatedByTheirOwnRules() {
            TimeDeposit basicEligible = deposit(1, BASIC, 1_200.00, 31);
            TimeDeposit studentExpired = deposit(2, STUDENT, 400.00, 366);
            TimeDeposit premiumEligible = deposit(3, PREMIUM, 240.00, 46);
            TimeDeposit basicWaiting = deposit(4, BASIC, 1_200.00, 30);
            TimeDeposit studentLastDay = deposit(5, STUDENT, 400.00, 365);
            TimeDeposit premiumWaiting = deposit(6, PREMIUM, 240.00, 45);

            calculator.updateBalance(Arrays.asList(
                    basicEligible, studentExpired, premiumEligible, basicWaiting, studentLastDay, premiumWaiting));

            assertThat(basicEligible.getBalance()).isEqualTo(1_201.00);
            assertThat(studentExpired.getBalance()).isEqualTo(400.00);
            assertThat(premiumEligible.getBalance()).isEqualTo(241.00);
            assertThat(basicWaiting.getBalance()).isEqualTo(1_200.00);
            assertThat(studentLastDay.getBalance()).isEqualTo(401.00);
            assertThat(premiumWaiting.getBalance()).isEqualTo(240.00);
        }

        @Test
        void interestDoesNotCarryOverFromAnEligibleToAFollowingIneligibleDeposit() {
            TimeDeposit eligible = deposit(1, PREMIUM, 12_000.00, 46);
            TimeDeposit ineligible = deposit(2, BASIC, 1_200.00, 10);

            calculator.updateBalance(Arrays.asList(eligible, ineligible));

            assertThat(eligible.getBalance()).isEqualTo(12_050.00);
            assertThat(ineligible.getBalance()).isEqualTo(1_200.00);
        }

        @Test
        void ineligibleDepositDoesNotSuppressInterestOfAFollowingEligibleDeposit() {
            TimeDeposit ineligible = deposit(1, PREMIUM, 12_000.00, 45);
            TimeDeposit eligible = deposit(2, BASIC, 1_200.00, 31);

            calculator.updateBalance(Arrays.asList(ineligible, eligible));

            assertThat(ineligible.getBalance()).isEqualTo(12_000.00);
            assertThat(eligible.getBalance()).isEqualTo(1_201.00);
        }

        @Test
        void sameDepositReferencedTwiceIsUpdatedTwice() {
            TimeDeposit deposit = deposit(BASIC, 1_200.00, 31);

            calculator.updateBalance(Arrays.asList(deposit, deposit));

            assertThat(deposit.getBalance()).isEqualTo(1_202.00);
        }

        @Test
        void depositsInAnImmutableListAreUpdatedInPlace() {
            TimeDeposit first = deposit(1, BASIC, 1_200.00, 31);
            TimeDeposit second = deposit(2, PREMIUM, 240.00, 46);

            calculator.updateBalance(List.of(first, second));

            assertThat(first.getBalance()).isEqualTo(1_201.00);
            assertThat(second.getBalance()).isEqualTo(241.00);
        }

        @Test
        void listSizeOrderAndReferencesArePreserved() {
            TimeDeposit first = deposit(1, BASIC, 1_200.00, 31);
            TimeDeposit second = deposit(2, STUDENT, 400.00, 10);
            TimeDeposit third = deposit(3, PREMIUM, 240.00, 46);
            List<TimeDeposit> deposits = new ArrayList<>(List.of(first, second, third));

            calculator.updateBalance(deposits);

            assertThat(deposits).hasSize(3);
            assertThat(deposits.get(0)).isSameAs(first);
            assertThat(deposits.get(1)).isSameAs(second);
            assertThat(deposits.get(2)).isSameAs(third);
        }
    }

    @Nested
    @DisplayName("Object state preservation")
    class StatePreservation {

        @ParameterizedTest(name = "{0}, day {1}")
        @CsvSource({"BASIC, 31", "BASIC, 30", "STUDENT, 365", "STUDENT, 366", "PREMIUM, 46", "PREMIUM, 45"})
        void onlyTheBalanceMayChange(PlanType planType, int days) {
            TimeDeposit deposit = deposit(42, planType, 1_200.00, days);

            calculator.updateBalance(List.of(deposit));

            assertThat(deposit.getId()).isEqualTo(42);
            assertThat(deposit.getPlanType()).isSameAs(planType.getValue());
            assertThat(deposit.getDays()).isEqualTo(days);
        }

        @Test
        void eligibleDepositBalanceIsReplacedWithTheUpdatedValue() {
            TimeDeposit deposit = deposit(42, PREMIUM, 240.00, 46);

            calculator.updateBalance(List.of(deposit));

            assertThat(deposit.getBalance()).isEqualTo(241.00);
        }
    }

    /**
     * Invalid input handling is out of scope for the assignment. These tests only pin today's
     * behaviour so an accidental change during refactoring is visible. They are not requirements.
     */
    @Nested
    @DisplayName("Optional characterization: invalid input (no validation exists)")
    class OptionalInvalidInputCharacterization {

        @ParameterizedTest(name = "plan \"{0}\" earns nothing")
        @ValueSource(strings = {"BASIC", "Basic", "STUDENT", "Premium", "pReMiUm", "", " basic", "gold"})
        void planTypeIsMatchedExactlyAndUnknownPlansEarnNothing(String planType) {
            assertInterest(planType, 12_000.00, 100, 0.00);
        }

        @ParameterizedTest(name = "{0}, day {1} earns nothing")
        @CsvSource({
                "BASIC, -1", "STUDENT, -1", "PREMIUM, -1",
                "BASIC, -2147483648", "STUDENT, -2147483648", "PREMIUM, -2147483648"
        })
        void negativeDaysEarnNothing(PlanType planType, int days) {
            assertInterest(planType, 12_000.00, days, 0.00);
        }

        @Test
        void negativeBalanceEarnsNegativeInterest() {
            assertThat(balanceAfterOneUpdate(BASIC, -1_200.00, 31)).isEqualTo(-1_201.00);
        }

        @Test
        void nullPlanTypeIsIgnoredDuringTheWaitingPeriod() {
            assertThat(balanceAfterOneUpdate((String) null, 1_200.00, 30)).isEqualTo(1_200.00);
        }

        @Test
        void nullPlanTypeThrowsOnceTheDepositIsOlderThan30Days() {
            assertThatNullPointerException().isThrownBy(() -> balanceAfterOneUpdate((String) null, 1_200.00, 31));
        }

        @Test
        void nullBalanceThrowsEvenForAnIneligibleDeposit() {
            TimeDeposit deposit = new TimeDeposit(1, BASIC.getValue(), null, 10);

            assertThatNullPointerException().isThrownBy(() -> calculator.updateBalance(List.of(deposit)));
        }

        @Test
        void nullListThrows() {
            assertThatNullPointerException().isThrownBy(() -> calculator.updateBalance(null));
        }

        @Test
        void nullElementThrowsLeavingEarlierDepositsUpdatedAndLaterOnesUntouched() {
            TimeDeposit before = deposit(1, BASIC, 1_200.00, 31);
            TimeDeposit after = deposit(3, BASIC, 1_200.00, 31);

            assertThatNullPointerException()
                    .isThrownBy(() -> calculator.updateBalance(Arrays.asList(before, null, after)));
            assertThat(before.getBalance()).isEqualTo(1_201.00);
            assertThat(after.getBalance()).isEqualTo(1_200.00);
        }
    }
}
