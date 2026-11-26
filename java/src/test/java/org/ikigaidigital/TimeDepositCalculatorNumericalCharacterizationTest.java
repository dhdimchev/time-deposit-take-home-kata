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

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.ikigaidigital.domain.model.PlanType.*;
import static org.ikigaidigital.TimeDepositTestSupport.*;

/**
 * Characterizes the <em>numerical</em> behaviour of {@link TimeDepositCalculator#updateBalance(List)}.
 * The calculator uses a {@code double} balance, computes the interest in {@code double}, rounds it with
 * {@code new BigDecimal(double).setScale(2, HALF_UP)} and adds it back as a {@code double}.
 * <p>
 * These tests pin existing behaviour, quirks included. They are kept apart from the business-rule tests
 * because they may legitimately change if the monetary representation is improved (e.g. to BigDecimal).
 * <p>
 * Where rounding depends on the binary value of the interest, the comment states that exact value
 * (as printed by {@code new BigDecimal(double)}). Expected interest literals are derived by hand,
 * never with the production formula.
 */
class TimeDepositCalculatorNumericalCharacterizationTest {

    private final TimeDepositCalculator calculator = new TimeDepositCalculator();

    @Nested
    @DisplayName("Rounding of the monthly interest")
    class Rounding {

        @Test
        void zeroBalanceStaysZero() {
            assertThat(balanceAfterOneUpdate(BASIC, 0.00, 31)).isEqualTo(0.00);
        }

        @ParameterizedTest(name = "{0}: one cent earns nothing")
        @EnumSource(PlanType.class)
        void oneCentBalanceEarnsNothing(PlanType planType) {
            assertThat(balanceAfterOneUpdate(planType, 0.01, 100)).isEqualTo(0.01);
        }

        @Test
        void interestJustBelowHalfACentRoundsToZero() {
            // 5.99 / 1200 = 0.0049916...
            assertInterest(BASIC, 5.99, 31, 0.00);
        }

        @ParameterizedTest(name = "{0}, balance {1}: interest of half a cent rounds up to 0.01")
        @CsvSource({
                // each computes to the double 0.005 = 0.005000000000000000104083408558608425664715468883514404296875
                "BASIC,   6.00",
                "STUDENT, 2.00",
                "PREMIUM, 1.20"
        })
        void interestOfExactlyHalfACentRoundsUpForEveryPlan(PlanType planType, double balance) {
            assertInterest(planType, balance, 100, 0.01);
        }

        @ParameterizedTest(name = "{0}, balance {1}: exact binary tie {2} rounds HALF_UP to {3}")
        @CsvSource({
                // 0.125 is exactly representable in binary, so these are true ties.
                // HALF_EVEN or HALF_DOWN would give 0.12 / -0.12.
                "BASIC,    150.00,  0.125,  0.13",
                "STUDENT,   50.00,  0.125,  0.13",
                "PREMIUM,   30.00,  0.125,  0.13",
                "BASIC,   -150.00, -0.125, -0.13"
        })
        void exactBinaryTiesRoundHalfUpAwayFromZero(
                PlanType planType, double balance, String exactInterest, double expectedInterest) {
            assertInterest(planType, balance, 100, expectedInterest);
        }

        @ParameterizedTest(name = "{0}, balance {1}: interest {2}")
        @CsvSource({
                // double 0.024999999999999997918...; balance * (0.03 / 12) gives 0.03
                "STUDENT,  10.00, 0.02",
                // double 0.054999999999999993338...; balance * (0.03 / 12) and balance * 3 / 100 / 12 give 0.06
                "STUDENT,  22.00, 0.05",
                // double 0.095000000000000014988...; balance * 1 / 100 / 12 gives 0.09
                "BASIC,   114.00, 0.10"
        })
        void interestDependsOnTheExactRateExpressionBalanceTimesRateDividedBy12(
                PlanType planType, double balance, double expectedInterest) {
            assertInterest(planType, balance, 100, expectedInterest);
        }

        @Test
        void interestJustAboveHalfACentRoundsUp() {
            // 6.01 / 1200 = 0.0050083...
            assertInterest(BASIC, 6.01, 31, 0.01);
        }

        @Test
        void interestRoundsUpToTheNextCent() {
            // 1210 / 1200 = 1.008333...
            assertInterest(BASIC, 1_210.00, 31, 1.01);
        }

        @Test
        void interestRoundsDownToTheCent() {
            // 1205 / 1200 = 1.0041666...
            assertInterest(BASIC, 1_205.00, 31, 1.00);
        }

        @ParameterizedTest(name = "{0}, balance {1}: decimal tie rounds DOWN to {2}")
        @CsvSource({
                // decimal 0.015, but the double is 0.01499999999999999944488848768742172978818416595458984375
                "BASIC,   18.00, 0.01",
                "STUDENT,  6.00, 0.01",
                // decimal 0.025, but the double is 0.0249999999999999979183318288278314867056906223297119140625
                "BASIC,   30.00, 0.02",
                // decimal 0.075, but the double is 0.07499999999999999722444243843710864894092082977294921875
                "PREMIUM, 18.00, 0.07",
                // decimal 0.105, but the double is 0.10499999999999999611421941381195210851728916168212890625
                "PREMIUM, 25.20, 0.10"
        })
        void decimalHalfCentTiesWhoseBinaryValueIsBelowTheTieRoundDown(
                PlanType planType, double balance, double expectedInterest) {
            // Mathematically HALF_UP would give one more cent; new BigDecimal(double) sees the binary value.
            // Exact decimal arithmetic would change all of these results. BigDecimal.valueOf(double) would change
            // every row except basic 30.00, whose double prints as 0.024999999999999998.
            assertInterest(planType, balance, 100, expectedInterest);
        }

        @ParameterizedTest(name = "PREMIUM, balance {0}: decimal tie rounds UP to {1}")
        @CsvSource({
                // decimal 0.015, the double is 0.01500000000000000117961196366422882420010864734649658203125
                "3.60,  0.02",
                // decimal 0.055, the double is 0.055000000000000000277555756156289135105907917022705078125
                "13.20, 0.06"
        })
        void decimalHalfCentTiesWhoseBinaryValueIsAboveTheTieRoundUp(double balance, double expectedInterest) {
            // Computing balance * (rate / 12) instead of balance * rate / 12 would round these down.
            assertInterest(PREMIUM, balance, 100, expectedInterest);
        }

        @Test
        void negativeHalfCentInterestRoundsAwayFromZero() {
            // -6.00 / 1200 = -0.005 (binary -0.00500000000000000010...) -> HALF_UP -> -0.01
            assertThat(balanceAfterOneUpdate(BASIC, -6.00, 31)).isEqualTo(-6.01);
        }

        @Test
        void onlyTheInterestIsRoundedNotTheResultingBalance() {
            // 100.123 / 1200 = 0.0834358... -> 0.08; the third decimal of the balance is kept
            assertThat(balanceAfterOneUpdate(BASIC, 100.123, 31)).isEqualTo(100.203);
        }

        @Test
        void balanceCanCrossAWholeNumberBoundary() {
            // 99.95 / 1200 = 0.0832916... -> 0.08
            assertInterest(BASIC, 99.95, 31, 0.08);
            assertThat(balanceAfterOneUpdate(BASIC, 99.95, 31)).isGreaterThan(100.00);
        }

        @Test
        void largeMonetaryBalanceKeepsCentPrecision() {
            // 1,000,000,000,000.00 / 1200 = 833,333,333.333... -> 833,333,333.33
            assertThat(balanceAfterOneUpdate(BASIC, 1_000_000_000_000.00, 31)).isEqualTo(1_000_833_333_333.33);
        }

        @ParameterizedTest(name = "BASIC, day {0}: -0.0 becomes +0.0")
        @ValueSource(ints = {10, 31})
        void negativeZeroBalanceIsNormalisedToPositiveZero(int days) {
            // setBalance is called even when no interest applies, and -0.0 + 0.0 == +0.0
            double result = balanceAfterOneUpdate(BASIC, -0.0, days);

            assertThat(Double.doubleToRawLongBits(result)).isEqualTo(Double.doubleToRawLongBits(0.0));
        }
    }

    @Nested
    @DisplayName("Overflow, underflow and numerical limits (existing behaviour, not fixed)")
    class NumericalLimits {

        @ParameterizedTest(name = "{0}: eligible MAX_VALUE overflows to +Infinity")
        @EnumSource(PlanType.class)
        void eligibleMaxValueBalanceOverflowsToInfinity(PlanType planType) {
            assertThat(balanceAfterOneUpdate(planType, Double.MAX_VALUE, 100)).isEqualTo(Double.POSITIVE_INFINITY);
        }

        @ParameterizedTest(name = "{0}, day {1}: ineligible MAX_VALUE is unchanged")
        @CsvSource({"BASIC, 30", "STUDENT, 30", "STUDENT, 366", "PREMIUM, 30", "PREMIUM, 45"})
        void ineligibleMaxValueBalanceIsUnchanged(PlanType planType, int days) {
            assertThat(balanceAfterOneUpdate(planType, Double.MAX_VALUE, days)).isEqualTo(Double.MAX_VALUE);
        }

        @Test
        void halfOfMaxValueGrowsAndStaysFinite() {
            double balance = Double.MAX_VALUE / 2;

            double result = balanceAfterOneUpdate(BASIC, balance, 31);

            assertThat(result).isFinite().isGreaterThan(balance);
        }

        // The overflow threshold is MAX_VALUE / (1 + rate / 12): ~0.99917 (basic), ~0.99751 (student),
        // ~0.99585 (premium) of MAX_VALUE. 0.99 is below every threshold, 0.9995 is above every threshold.
        @ParameterizedTest(name = "{0}: 0.99 * MAX_VALUE stays finite")
        @EnumSource(PlanType.class)
        void balanceJustBelowTheOverflowThresholdStaysFinite(PlanType planType) {
            double balance = Double.MAX_VALUE * 0.99;

            double result = balanceAfterOneUpdate(planType, balance, 100);

            assertThat(result).isFinite().isGreaterThan(balance);
        }

        @ParameterizedTest(name = "{0}: 0.9995 * MAX_VALUE overflows")
        @EnumSource(PlanType.class)
        void balanceJustAboveTheOverflowThresholdOverflowsToInfinity(PlanType planType) {
            assertThat(balanceAfterOneUpdate(planType, Double.MAX_VALUE * 0.9995, 100))
                    .isEqualTo(Double.POSITIVE_INFINITY);
        }

        @ParameterizedTest(name = "{0}: MIN_VALUE and MIN_NORMAL are unchanged")
        @EnumSource(PlanType.class)
        void tinyPositiveBalancesUnderflowToZeroInterest(PlanType planType) {
            assertThat(balanceAfterOneUpdate(planType, Double.MIN_VALUE, 100)).isEqualTo(Double.MIN_VALUE);
            assertThat(balanceAfterOneUpdate(planType, Double.MIN_NORMAL, 100)).isEqualTo(Double.MIN_NORMAL);
        }

        @Test
        void invokingAgainAfterOverflowThrowsNumberFormatException() {
            TimeDeposit deposit = deposit(BASIC, Double.MAX_VALUE, 31);
            calculator.updateBalance(List.of(deposit));
            assertThat(deposit.getBalance()).isEqualTo(Double.POSITIVE_INFINITY);

            // Infinity interest cannot be converted by new BigDecimal(double)
            assertThatExceptionOfType(NumberFormatException.class)
                    .isThrownBy(() -> calculator.updateBalance(List.of(deposit)));
            assertThat(deposit.getBalance()).isEqualTo(Double.POSITIVE_INFINITY);
        }
    }

    /**
     * Optional: non-finite balances are invalid input, which is out of scope. These tests only
     * pin today's behaviour.
     */
    @Nested
    @DisplayName("Optional characterization: non-finite balances")
    class NonFiniteBalances {

        @Test
        void ineligibleNonFiniteBalancesPassThroughUnchanged() {
            assertThat(balanceAfterOneUpdate(BASIC, Double.NaN, 30)).isNaN();
            assertThat(balanceAfterOneUpdate(BASIC, Double.POSITIVE_INFINITY, 30)).isEqualTo(Double.POSITIVE_INFINITY);
            assertThat(balanceAfterOneUpdate(BASIC, Double.NEGATIVE_INFINITY, 30)).isEqualTo(Double.NEGATIVE_INFINITY);
        }

        @ParameterizedTest(name = "{0}, balance {1}: throws NumberFormatException")
        @CsvSource({
                "BASIC, NaN", "BASIC, Infinity", "BASIC, -Infinity",
                "STUDENT, NaN", "STUDENT, Infinity", "STUDENT, -Infinity",
                "PREMIUM, NaN", "PREMIUM, Infinity", "PREMIUM, -Infinity"
        })
        void eligibleNonFiniteBalancesThrowNumberFormatException(PlanType planType, double balance) {
            assertThatExceptionOfType(NumberFormatException.class)
                    .isThrownBy(() -> balanceAfterOneUpdate(planType, balance, 100));
        }

        @Test
        void exceptionMidListLeavesEarlierDepositsUpdatedAndLaterOnesUntouched() {
            TimeDeposit before = deposit(1, BASIC, 1_200.00, 31);
            TimeDeposit poisoned = deposit(2, BASIC, Double.POSITIVE_INFINITY, 31);
            TimeDeposit after = deposit(3, BASIC, 1_200.00, 31);

            assertThatExceptionOfType(NumberFormatException.class)
                    .isThrownBy(() -> calculator.updateBalance(Arrays.asList(before, poisoned, after)));

            assertThat(before.getBalance()).isEqualTo(1_201.00);
            assertThat(after.getBalance()).isEqualTo(1_200.00);
        }
    }
}
