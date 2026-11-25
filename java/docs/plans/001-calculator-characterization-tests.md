# 001 – Characterization Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Pin the current behaviour of `TimeDepositCalculator.updateBalance` with an exhaustive JUnit 5 suite, so the calculator can be refactored safely.

**Architecture:** Business-rule tests live in `TimeDepositCalculatorTest`, in `@Nested` groups. Numerical precision, overflow and underflow characterization lives in a separate class, `TimeDepositCalculatorNumericalCharacterizationTest`, so it can be revised if the monetary representation changes. Both use a shared package-private helper, `TimeDepositTestSupport`. Expected interest values are hand-derived literals. Where the binary value matters, a comment cites its exact IEEE-754 value.

**Tech Stack:** Java 17, JUnit 5.9.0 (engine + params), AssertJ 3.23.1, Maven. JaCoCo runs from the CLI for coverage only.

**Spec:** `docs/specs/001-calculator-characterization-tests.md`

## Global Constraints
- Never modify `src/main/**`. Never refactor the calculator or `TimeDeposit`.
- Only build change: `junit-jupiter-params:5.9.0`, test scope. No Spring, Mockito or Testcontainers.
- No commits. The user commits manually.
- No withdrawal tests.
- Monetary assertions use exact `isEqualTo`. No `isCloseTo` or offsets. Signed zero is asserted via `Double.doubleToRawLongBits`.
- Tests never compute `balance * rate / 12`. Expected interest is a literal. The expected balance is either a literal or `balance + <interest literal>`, because adding in `double` is part of the contract.
- Characterization tests pass on first run by design. They are not flipped manually to check that they fail (the user asked to skip that).
- No withdrawal logic or tests at this stage.
- The existing `updateBalance_Test` is preserved unchanged.

## Review Focus
1. Boundary tests that pass even if the boundary moves. Each boundary is tested on both sides: the eligible side asserts an exact non-zero interest, and the ineligible side asserts exactly zero interest.
2. Half-cent cases whose literals assume decimal arithmetic. These must come from the exact binary values (see table in Task 3).
3. NaN/-0.0 assertions that pass vacuously. Use raw bits or `isNaN()`.
4. A null or non-finite input in the middle of a list. Assert the deposits before it were already updated.
5. Duplicate references in one list. They are updated twice.

---

### Task 1: Dependency and test skeleton
**Files:** Modify `pom.xml` (dependencies). Create `src/test/java/org/ikigaidigital/TimeDepositTestSupport.java`.

**Produces (static helpers in `TimeDepositTestSupport`):**
- `static final String BASIC = "basic", STUDENT = "student", PREMIUM = "premium";`
- `TimeDeposit deposit(String plan, double balance, int days)`, with id `1` (overload adding `int id`)
- `double balanceAfterOneUpdate(String plan, double balance, int days)`
- `void assertInterest(String plan, double balance, int days, double expectedInterest)`: asserts `balanceAfterOneUpdate(...)` is `isEqualTo(balance + expectedInterest)`

- [x] Add `junit-jupiter-params` 5.9.0 test scope.
- [x] Keep `updateBalance_Test` unchanged. Add a separate test for the same input: basic 1,234,567.00 at day 45 → interest 1028.81 (1,234,567/1200 = 1028.8058…) → balance `1_235_595.81`.
- [x] `mvn test`, expect it to pass.

### Task 2: Business rules (`@Nested BusinessRules`) – AC1
- [x] `@ParameterizedTest` boundary tables, with plan-specific clean balances:
  - basic 1200: days 0, 29, 30 → 0.00; days 31, 32, 365, 366, `Integer.MAX_VALUE` → 1.00
  - student 400: days 0, 29, 30, 366, 367, `MAX` → 0.00; days 31, 32, 364, 365 → 1.00
  - premium 240: days 0, 29, 30, 31, 44, 45 → 0.00; days 46, 47, 365, 366, `MAX` → 1.00
- [x] Monthly rate (an annual rate divided by 12): basic 12,000 → 10.00, student 12,000 → 30.00, premium 12,000 → 50.00. Relative ordering basic < student < premium on the same balance and day 100.
- [x] Not prorated: basic 1200 at days 31, 100, 365, 10,000 → each exactly 1.00.
- [x] Interest is on the current balance: basic 2400 → 2.00 (twice the 1200 case).
- [x] One month per call: premium 240 at day 400 → 1.00, not several months (no retroactive interest).

### Task 3: Rounding (`TimeDepositCalculatorNumericalCharacterizationTest`, `@Nested Rounding`) – AC2
Expected interest per case. The exact binary value comes from `new BigDecimal(double)`:

| case | plan / balance / day | interest |
|---|---|---|
| zero | basic 0.00 / 31 | 0.00 |
| one cent | each plan 0.01 / 100 | 0.00 |
| below half-cent | basic 5.99 / 31 (0.004991…) | 0.00 |
| at half-cent (binary 0.00500000000000000010…) | basic 6.00, student 2.00, premium 1.20 / 100 | 0.01 |
| above half-cent | basic 6.01 / 31 | 0.01 |
| rounds up | basic 1210 / 31 (1.00833) | 1.01 |
| rounds down | basic 1205 / 31 (1.00416) | 1.00 |
| x.xx5 rounds down (binary 0.014999…) | basic 18.00, student 6.00 / 100 | 0.01 |
| x.xx5 rounds down (binary 0.074999…) | premium 18.00 / 100 | 0.07 |
| x.xx5 rounds down (binary 0.024999…) | basic 30.00 / 100 | 0.02 |
| x.xx5 rounds up (binary 0.0150000…12) | premium 3.60 / 100 | 0.02 |
| x.xx5 rounds up (binary 0.0550000…28) | premium 13.20 / 100 | 0.06 |
| more than 2 decimals, final balance not rounded | basic 100.123 / 31 | 0.08 → literal `100.203` |
| crosses a whole number | basic 99.95 / 31 (0.08329) | 0.08 |
| large | basic 1,000,000,000,000.00 / 31 | 833,333,333.33 → literal `1_000_833_333_333.33` |
| signed zero | basic -0.0 at days 31 and 10 | result raw bits = raw bits of `+0.0` |

### Task 4: Numerical limits (`TimeDepositCalculatorNumericalCharacterizationTest`, `@Nested NumericalLimits`) – AC3
- [x] `Double.MAX_VALUE` eligible (basic/student/premium at day 100) → `Double.POSITIVE_INFINITY`.
- [x] `MAX_VALUE` ineligible (each plan at day 30; student at day 366; premium at day 45) → `MAX_VALUE` unchanged.
- [x] `MAX_VALUE / 2` basic at day 31 → finite and greater than the input.
- [x] Overflow threshold (b·(1 + r/12) = MAX): balance `MAX_VALUE * 0.99` → finite and greater than the input, for each plan; `MAX_VALUE * 0.9995` → `+Infinity`, for each plan.
- [x] `MIN_VALUE` and `MIN_NORMAL` eligible (each plan) → unchanged (the interest underflows to 0.00).
- [x] Overflow and then a second call → `NumberFormatException` (from `new BigDecimal(Infinity)`).

### Task 5: Repeated calls, collections, state (`@Nested RepeatedInvocation`, `CollectionProcessing`, `StatePreservation`) – AC4–6
- [x] Twice: basic 1200 → 1201.00 → 1202.00. Compounding: basic 12,000 over 3 calls → 12,010.00, 12,020.01, 12,030.03. This shows the second call uses the updated balance, because 12,010/1200 rounds to 10.01.
- [x] Ineligible deposit called 5 times → unchanged. Days are still the original value after several calls.
- [x] Empty `ArrayList` → no exception, still empty. Single deposit. Same plan with different balances. Mixed plans/days, eligible→ineligible and ineligible→eligible. Each deposit gets its own expected value, so no carry-over between deposits.
- [x] Same object twice in one list → updated twice (1200 → 1202.00).
- [x] `List.of(...)` immutable list → deposits are updated, no exception.
- [x] Size, order (by identity) and references preserved. id, planType and days unchanged.

### Task 6: Optional exceptional input (`@Nested OptionalExceptionalInput`) – AC7
- [x] Plan "BASIC", "Premium", "", "gold" at day 100 → 0 interest.
- [x] Days -1 and `Integer.MIN_VALUE` → 0 interest (basic 1200).
- [x] Negative balance: basic -1200 → -1201.00; basic -6.00 → -6.01 (HALF_UP away from zero).
- [x] NaN / ±Infinity at day 30 → passes through (NaN checked with `isNaN`). At day 31 basic → `NumberFormatException`.
- [x] Null plan: day 30 → unchanged; day 31 → NPE.
- [x] Null balance (boxed `Double`): day 10 → NPE.
- [x] Null list → NPE. List `[valid, null]` → NPE, and the first deposit is already updated.
- [x] `[basic 1200 day 31, basic +Infinity day 31, basic 1200 day 31]` → `NumberFormatException`. The first deposit is updated, the third is untouched.

### Task 7: Verify and review
- [x] `mvn verify` passes. Record the test count.
- [x] Coverage: `mvn org.jacoco:jacoco-maven-plugin:0.8.14:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.14:report`, then read `target/site/jacoco/jacoco.csv`. Target: 100% line and branch coverage for `TimeDepositCalculator`. If it fails on JDK 27, use IntelliJ coverage.
- [x] Self-review for weak assertions, duplication and missing boundaries. Write the summary for the user.
