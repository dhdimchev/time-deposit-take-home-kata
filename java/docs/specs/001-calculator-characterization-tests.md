# 001 – Characterization tests for `TimeDepositCalculator`

## What
A comprehensive JUnit 5 unit test suite that pins the **current observable behaviour** of
`TimeDepositCalculator.updateBalance(List<TimeDeposit>)`, including its numerical quirks.

## Why
The calculator will be refactored manually (e.g. per-plan interest strategies). The brief demands that
its behaviour stays identical. Today the only test is a placeholder (`assertThat(1).isEqualTo(1)`), so
there is no safety net.

## Scope
- In: tests for `TimeDepositCalculator` and its use of `TimeDeposit`.
- Out: any change to production code, any refactoring, validation or bug fixes.
- Out: withdrawal scenarios. `TimeDeposit` has no withdrawals, and the developer decided these are not needed now.
- Build change (approved): add `org.junit.jupiter:junit-jupiter-params:5.9.0` (test scope) to `pom.xml`.
  Nothing else changes in the build.
- Coverage: measured with the JaCoCo Maven plugin invoked from the CLI (no pom change). If JaCoCo cannot
  run on the local JDK 27, fall back to IntelliJ coverage.

## Behaviour being characterized (as implemented)
| Rule | Implementation |
|---|---|
| Waiting period | no interest when `days <= 30` (any plan) |
| basic | `balance * 0.01 / 12` when `days > 30` |
| student | `balance * 0.03 / 12` when `30 < days < 366` |
| premium | `balance * 0.05 / 12` when `days > 45` |
| other / unknown plan (case-sensitive match) | 0 interest |
| Rounding | the `double` interest goes through `new BigDecimal(double).setScale(2, HALF_UP)`, then the result is added to the `double` balance. The final balance is **not** rounded. |
| Side effects | `setBalance` is called on **every** deposit, even when interest is 0. Days are never changed. The list is not structurally changed. |

## Test design rules
- JUnit 5 + AssertJ only (no Spring, Mockito or Testcontainers). Use `@Nested` and `@ParameterizedTest`.
- Expected values are derived **independently**: by hand from the plan rate, or from the exact binary value
  of the IEEE-754 interest (documented in a comment). Tests never re-implement the calculator formula.
- Monetary assertions use exact `isEqualTo` on `double`. No `isCloseTo` or offsets.
- Signed zero is asserted via `Double.doubleToRawLongBits`.
- Every threshold (30/31, 45/46, 365/366) is tested on both sides, and the eligible side must have a
  non-zero exact interest so a moved boundary fails the test.
- The existing `updateBalance_Test` method keeps its name, but it now asserts a real outcome.

## Acceptance criteria
1. **Business rules.** Every plan is tested at the days listed in the request (basic 0/29/30/31/32/365/366;
   student 0/29/30/31/32/364/365/366/367; premium 0/29/30/31/44/45/46/47/365/366), plus `Integer.MAX_VALUE`.
   Each plan's monthly rate is verified on a clean balance (basic 1200 → +1.00, student 400 → +1.00,
   premium 240 → +1.00). Interest is relative to the current balance and is not prorated by days. Exactly one
   month is applied per call.
2. **Rounding.** Covers zero, one cent, interest below/at/above half a cent, rounding up and down, each plan's
   half-cent boundary, balances with more than 2 decimals (final balance is not rounded), crossing a
   whole-number boundary, large balances, and `-0.0` (becomes `+0.0`). Covers `new BigDecimal(double)`
   artifacts where the mathematically exact x.xx5 rounds **down** (e.g. basic 18.00 → +0.01; student
   6.00 → +0.01; premium 18.00 → +0.07). Also covers balances where an algebraically equivalent formula
   would round differently (`b*(r/12)`: basic 30.00, premium 3.60, premium 13.20).
3. **Numerical limits.** `Double.MAX_VALUE` eligible (→ `Infinity`) and ineligible (unchanged) for each plan,
   `MAX_VALUE/2`, below and above the overflow threshold, `MIN_VALUE`, `MIN_NORMAL`, and `Integer.MAX_VALUE`
   days. A repeated call on an overflowed (`Infinity`) eligible balance throws `NumberFormatException`.
4. **Repeated invocation.** Compounding uses the updated balance. Ineligible deposits stay unchanged.
   Days are not incremented.
5. **Collections.** Empty list, single deposit, same or mixed plans/balances/days, eligible/ineligible
   ordering, no carry-over of interest between deposits, the same object listed twice (updated twice),
   `List.of(...)` immutable list, and list size/order/references preserved.
6. **State preservation.** id, planType and days are unchanged; only the balance changes.
7. **Optional characterization (exceptional input, no fixes).** Uppercase, mixed-case, empty and unknown
   plan → 0 interest. Negative and `Integer.MIN_VALUE` days → 0. Negative balance (HALF_UP away from zero:
   −6.00 basic → −6.01). NaN/±Infinity: ineligible ones pass through, eligible ones throw
   `NumberFormatException`. Null plan: NPE only when `days > 30`. Null balance: NPE even when ineligible.
   Null list: NPE. Null element: NPE. An exception part-way through leaves earlier deposits already updated.
8. All tests pass against the **current** implementation (`mvn verify` green). Calculator line and branch
   coverage is 100% if measurable.

## Discrepancies and assumptions to report
- The brief says "1% / 3% / 5% interest" monthly. The code applies the **annual** rate / 12 per call.
- Intended HALF_UP does not always apply to the decimal value: `new BigDecimal(double)` rounds 0.015 → 0.01.
- Student "no interest after 1 year": day 365 still earns interest. 1 year = 365 days (leap years ignored).
- Premium "starts after 45 days": the first eligible day is 46.
- The brief requires `withdrawals` on deposits, but `TimeDeposit` has none and the calculator ignores them.
- The brief's DB `balance` is Decimal, but the domain uses `Double`. The final balance can carry more than 2 decimals.
- Plan-type matching is case-sensitive, and the calculator never changes `days`. The caller is responsible.
