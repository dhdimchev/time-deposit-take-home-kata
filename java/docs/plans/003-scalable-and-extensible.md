# 003 – Extensible Interest Rules and Scalable Endpoints Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- Per-plan interest strategies behind the unchanged `TimeDepositCalculator.updateBalance`.
- A batched, idempotent, resumable `POST /time-deposits/balance-updates`.
- A streaming `GET /time-deposits`.

**Architecture:** the hexagonal layout from `CLAUDE.md` is unchanged.
- Strategies live in `domain.service.interest` (framework-free).
- `business.TimeDepositService` orchestrates the run loop and the per-batch transactions through ports only.
- `repository.impl` implements the run-tracking port and the batch and cursor queries.
- `rest` streams the domain objects it gets from `business`.

**Tech Stack:** unchanged from plan 002. JDBC access goes through `JdbcTemplate` / `JdbcClient`, which share the JPA
transaction.

**Spec:** `docs/specs/003-scalable-and-extensible.md`

## Global Constraints
- The spec-001 test files are not modified. The 146 characterization tests pass after every task.
- `updateBalance(List<TimeDeposit>)` keeps its signature and results. The no-argument constructor
  `new TimeDepositCalculator()` keeps working.
- No framework imports in `domain`. `rest` never imports `repository`. `business` never imports `repository.impl`.
  `ArchitectureTest` stays green.
- Exactly two API operations. The paths, schemas and status codes in `openapi.yaml` are unchanged.
- No Mockito: use hand-written fakes. No commits: the developer reviews and commits.
- Every integration test runs with `time-deposits.balance-update.batch-size=2`, set in `PostgresIntegrationTest`, so
  multi-batch runs are exercised on the 6 seeded deposits.

## Review Focus
1. **A run crashing between batches.** A retry must finish only the remaining deposits. Pinned in Task 3:
   `resumesAnInterruptedRun…` (IT) and `failureMidRunLeavesTheRunOpenForResume` (unit).
2. **A worker that finishes while another worker still holds rows.** It must wait and rescan, not return early or
   skip rows. Pinned in Task 3: `rescansUntilTheRunIsComplete` (unit) and `overlappingUpdatesPayOneMonth` (IT).
3. **Two `POST`s racing to start a run.** Exactly one run must be open. Pinned in Task 2:
   `startOrResumeRunReturnsTheSameOpenRunForConcurrentCallers`.
4. **Row grouping in the cursor query:** a deposit without withdrawals, a deposit with several, and the last
   deposit in the result. Pinned in Task 4.
5. **The `null` plan and unknown plans after the strategy refactor.** A `null` plan must still throw an NPE when
   `days > 30`, and an unknown plan must earn 0. Pinned by the spec-001 tests and by Task 1's `defaultCalculator…`
   tests.

---

### Task 1: Per-plan interest strategies

**Files:**
- Create in `src/main/java/org/ikigaidigital/domain/service/interest/`:
  - `InterestStrategy.java`:
    - `String planType()`
    - `double monthlyInterest(TimeDeposit deposit)`
  - `PlanInterestStrategy.java`:
    - abstract, constructor `(PlanType plan)`;
    - `planType()` returns `plan.getValue()`;
    - `protected abstract boolean qualifies(TimeDeposit deposit)`;
    - `final monthlyInterest` returns `qualifies ? deposit.getBalance() * plan.getAnnualInterestRate() / 12 : 0`. Keep
      exactly this evaluation order, with a comment pointing to spec 001.
  - `BasicInterestStrategy.java`: always qualifies.
  - `StudentInterestStrategy.java`: `days <= 365` (constant `MAX_INTEREST_DAYS`).
  - `PremiumInterestStrategy.java`: `days > 45` (constant `INTEREST_FREE_DAYS`).
- Modify `domain/service/TimeDepositCalculator.java`:
  - add constructor `TimeDepositCalculator(List<InterestStrategy> strategies)`, which builds a
    `Map.copyOf(Map<String, InterestStrategy>)` and throws `IllegalArgumentException` on duplicate plan values;
  - the no-argument constructor delegates with the three default strategies;
  - `monthlyInterest` becomes:
    - `days <= 30` → 0;
    - `Objects.requireNonNull(planType, …)`, with a comment that this preserves the legacy NPE;
    - `strategies.get(planType)`, where a missing strategy → 0, otherwise `strategy.monthlyInterest(deposit)`.
  - Remove the plan-specific constants and the `switch`. Keep `INTEREST_FREE_DAYS = 30` and the rounding unchanged.
- Modify `application/DomainConfiguration.java`: three `@Bean`s, one per default strategy, and
  `@Bean TimeDepositCalculator timeDepositCalculator(List<InterestStrategy> strategies)`.
- Tests in `src/test/java/org/ikigaidigital/domain/service/interest/`:
  `BasicInterestStrategyTest`, `StudentInterestStrategyTest`, `PremiumInterestStrategyTest`,
  `TimeDepositCalculatorExtensionTest`.

- [x] **Step 1: Write the tests.**
  - **Strategy tests:** each asserts `planType()` (`"basic"` / `"student"` / `"premium"`). Each also asserts that
    `monthlyInterest` equals `balance * rate / 12` with that exact expression in the test, commented "the evaluation
    order is part of the contract (spec 001)". The qualifying edges:
    - student: day 365 earns, day 366 → `0.0`;
    - premium: day 45 → `0.0`, day 46 earns;
    - basic: day 31 and day `Integer.MAX_VALUE` both earn.
  - **`TimeDepositCalculatorExtensionTest`:**
    - `newPlanCanBeAddedWithoutEditingTheCalculator`: a test-local `"gold"` strategy that always returns
      `balance * 0.12 / 12`. Calculator built from the 3 defaults plus gold. Deposit `("gold", 1200.00, day 31)` →
      balance `1212.00`.
    - `defaultCalculatorGivesUnknownPlansNoInterest`: `"gold"` at 1200.00, day 31 stays `1200.00`.
    - `defaultCalculatorStillThrowsForANullPlanAfter30Days`: an NPE.
    - `duplicatePlanStrategiesAreRejected`: two basic strategies → `IllegalArgumentException`.
- [x] **Step 2: Run `mvn test`.** Expected: FAIL (compilation).
- [x] **Step 3: Implement** the files listed above.
- [x] **Step 4: Run `mvn verify`.** Expected: PASS. The 146 characterization tests are unchanged and the ITs are
  green.

### Task 2: Run tracking and batch persistence

**Files:**
- Create `src/main/resources/db/migration/V3__interest_runs.sql`:
  - the `interest_runs` table;
  - the partial unique index `interest_runs_one_open`;
  - the `time_deposits.last_interest_run_id` column (FK);
  - all exactly as in the spec, with a header comment saying these are additions to the brief's schema for
    idempotent, resumable updates.
- Create `src/main/java/org/ikigaidigital/repository/api/InterestRunRepository.java`:
  - `int startOrResumeRun()`
  - `boolean completeRunIfDone(int runId)`
- Modify `repository/api/TimeDepositRepository.java`, adding:
  - `List<TimeDeposit> findNextBatchForUpdate(int runId, int afterId, int batchSize)`
  - `void saveBatch(List<TimeDeposit> deposits, int runId)`
- Create `repository/impl/JdbcInterestRunRepository.java` (`@Repository`, package-private, `JdbcTemplate`):
  - `startOrResumeRun`: `insert into interest_runs default values on conflict ((completed_at is null)) where completed_at is null do nothing returning id`.
    If no row comes back, `select id from interest_runs where completed_at is null`. Loop if that select finds
    nothing (the run completed in between).
  - `completeRunIfDone`:
    `update interest_runs set completed_at = now() where id = ? and completed_at is null and not exists (select 1 from time_deposits where last_interest_run_id is distinct from ?)`.
    Return true if a row was updated or the run was already completed.
- Modify `repository/impl/TimeDepositEntity.java`: add `Integer lastInterestRunId`
  (`@Column(name = "last_interest_run_id")`) with a setter.
- Modify `repository/impl/TimeDepositJpaRepository.java`: add a native query
  `select * from time_deposits where id > :afterId and last_interest_run_id is distinct from :runId order by id limit :batchSize for update skip locked`
  returning `List<TimeDepositEntity>`.
- Modify `repository/impl/JpaTimeDepositRepository.java`:
  - `findNextBatchForUpdate` maps entities to the domain;
  - `saveBatch` sets the balance (`Money.toCents`), the days and `lastInterestRunId` on the managed entities.
- Test: `src/test/java/org/ikigaidigital/repository/impl/InterestRunRepositoryIT.java` and additions to
  `JpaTimeDepositRepositoryIT.java`.

- [x] **Step 1: Write the ITs.**
  - `InterestRunRepositoryIT`:
    - `startOrResumeRunCreatesOneOpenRunAndReturnsItAgain`: two sequential calls return the same id, and
      `interest_runs` has 1 open row.
    - `startOrResumeRunReturnsTheSameOpenRunForConcurrentCallers`: 8 threads call it together (start latch). All get
      the same id, with 1 row in the table.
    - `completeRunIfDoneIsFalseWhileDepositsRemainAndTrueWhenAllAreInTheRun`: false while deposits remain. After
      `update time_deposits set last_interest_run_id = ?` it is true, `completed_at` is set, and the next
      `startOrResumeRun` returns a new id.
  - `JpaTimeDepositRepositoryIT` additions:
    - `findNextBatchForUpdateReturnsDepositsAfterTheCursorNotYetInTheRun`:
      - batch size 2, `afterId` 0 → ids 1, 2;
      - `afterId` 2 → ids 3, 4;
      - after `saveBatch` of ids 1–2 with run R, a fresh call with `afterId` 0 → ids 3, 4.
    - `findNextBatchForUpdateSkipsRowsLockedByAnotherTransaction`: thread A locks ids 1–2 through
      `findNextBatchForUpdate` inside a `TransactionTemplate` and waits. The main thread's call (batch size 2,
      `afterId` 0) → ids 3, 4 immediately.
    - `saveBatchPersistsBalanceDaysAndRun`: balance `0.1 + 0.2` → `0.30`, days `75`, `last_interest_run_id` = R.
- [x] **Step 2: Run `mvn verify -Dit.test='InterestRunRepositoryIT,JpaTimeDepositRepositoryIT'`.** Expected: FAIL.
- [x] **Step 3: Implement** the files listed above. Leave the existing `findAllForUpdate` / `saveAll` in place; Task 3
  removes them.
- [x] **Step 4: Run `mvn verify`.** Expected: PASS.

### Task 3: Batched, resumable `updateAllBalances`

**Files:**
- Create `src/main/java/org/ikigaidigital/business/BalanceUpdateSettings.java`:
  `record BalanceUpdateSettings(int batchSize, Duration rescanBackoff)`.
- Create `src/main/java/org/ikigaidigital/application/BalanceUpdateConfiguration.java`:
  - `@ConfigurationProperties("time-deposits.balance-update")` binding `batch-size` (default 1000) and
    `rescan-backoff` (default `100ms`);
  - a `@Bean BalanceUpdateSettings`.
- Modify `src/main/resources/application.yaml`: add the two properties with their defaults.
- Modify `business/TimeDepositService.java`:
  - constructor `(TimeDepositRepository, InterestRunRepository, TimeDepositCalculator, DepositAgeing, TransactionOperations, BalanceUpdateSettings)`;
  - `updateAllBalances()` is **not** `@Transactional`. It does:
    1. `runId = startOrResumeRun()`;
    2. `do { afterId = 0; loop batches inside transactionOperations.execute(...): find → calculator → ageing → saveBatch; afterId = last id } while (!completeRunIfDone(runId) && backoff())`.
  - The backoff is `Thread.sleep(rescanBackoff)`. Pass `Duration.ZERO` in unit tests.
- Modify `repository/api/TimeDepositRepository.java` and `repository/impl/JpaTimeDepositRepository.java`: remove
  `findAllForUpdate` and `saveAll`. Remove the `@Lock` method from `TimeDepositJpaRepository`.
- Modify `test/.../application/PostgresIntegrationTest.java`: add the property
  `time-deposits.balance-update.batch-size=2`.
- Rewrite tests:
  - `business/InMemoryTimeDepositRepository.java` becomes a fake implementing both ports. It keeps deposits by id,
    the open run, and each deposit's run id. Optional hooks: `failOnSaveBatch(int callNumber)` and
    `holdRows(Set<Integer> ids, int releaseAfterCompleteCalls)`, which simulates another worker.
  - `business/TimeDepositServiceTest.java`
  - `business/TimeDepositServiceConcurrencyIT.java`
  - `rest/TimeDepositApiIT.java`

- [x] **Step 1: Write the unit tests** (`TimeDepositServiceTest`, batch size 2, `Duration.ZERO`).
  - `updateAllBalancesProcessesEveryDepositOnceAcrossBatches`: 5 deposits → each saved once, with interest per the
    plan rules and days +30.
  - `failureMidRunLeavesTheRunOpenForResume`:
    - the second `saveBatch` throws, so the exception propagates;
    - deposits from batch 1 are in the run, the others are not, and the run is still open;
    - calling `updateAllBalances()` again processes only the remaining deposits;
    - the run completes, and every deposit ends with exactly one month.
  - `rescansUntilTheRunIsComplete`: ids 3–4 are held by "another worker" for the first `completeRunIfDone` call, then
    released and still unprocessed. The service processes them on the rescan and completes the run.
  - `completedRunIsFollowedByANewRun`: two calls → two months, two run ids.
  - Remove the now-obsolete tests `getAllReturnsTheDetailsFromTheRepository` (Task 4 replaces it) and
    `updateAllBalancesReadsDepositsThroughTheLockingQuery`.
- [x] **Step 2: Write and update the integration tests.**
  - `TimeDepositServiceConcurrencyIT` (replacing `parallelUpdatesApplyTwoMonths`):
    `overlappingUpdatesPayOneMonth`. Two threads call `updateAllBalances()` together. Expected afterwards:
    - balances `1201.00, 401.00, 241.00, 1000.00, 400.00, 240.00`;
    - days `75, 130, 90, 50, 430, 70`;
    - `interest_runs` has exactly 1 row with `completed_at` set.
  - `TimeDepositApiIT`:
    - the existing `postUpdatesAllBalancesAndReturnsNoContent` and `secondUpdateUsesTheAgedDeposits` now run over 3
      batches and keep their expected values;
    - new `resumesAnInterruptedRunWithoutPayingTwice`:
      - SQL sets up the state of a run that crashed after batch 1: insert an open `interest_runs` row R; set deposits
        1 and 2 to balances `1201.00` / `401.00`, days `75` / `130`, and `last_interest_run_id = R`;
      - `POST` → the "after one update" values for all 6 deposits (1 and 2 untouched);
      - R is completed, and there is no second run.
  - `JpaTimeDepositRepositoryIT`: remove `findAllForUpdateBlocksConcurrentUpdatesUntilTheLockIsReleased` and
    `saveAllPersistsTwoDecimalBalancesAndDays`, which are superseded by the Task 2 tests.
- [x] **Step 3: Run `mvn test` and `mvn verify`.** Expected: FAIL.
- [x] **Step 4: Implement** the files listed above.
- [x] **Step 5: Run `mvn verify`.** Expected: PASS.

### Task 4: Streaming `GET /time-deposits`

**Files:**
- Modify `repository/api/TimeDepositRepository.java`:
  - add `void forEachWithWithdrawals(Consumer<TimeDepositDetails> consumer)`;
  - remove `findAllWithWithdrawals`.
- Modify `repository/impl/JpaTimeDepositRepository.java`:
  - implement it with `JdbcTemplate` (fetch size 500) and a `RowCallbackHandler`, over one query:
    `select d.id, d.plan_type, d.balance, d.days, w.id, w.amount, w.date from time_deposits d left join withdrawals w on w.time_deposit_id = d.id order by d.id, w.id`;
  - group consecutive rows per deposit, emit each group when the deposit id changes, and emit the last one after the
    loop;
  - remove the JPQL fetch-join method and the `@OrderBy` dependency.
- Modify `business/TimeDepositService.java`: replace `getAll()` with
  `@Transactional(readOnly = true) void forEachTimeDeposit(Consumer<TimeDepositDetails> consumer)`.
- Modify `src/main/resources/static/openapi.yaml`:
  - move `GET` to tag `TimeDepositQueries` and `POST` to tag `TimeDepositUpdates`, so the generator produces two
    interfaces;
  - add a description sentence saying the list is streamed;
  - paths, schemas and status codes are unchanged.
- Modify `rest/controller/TimeDepositController.java`:
  - `implements TimeDepositUpdatesApi` for `POST`;
  - `GET` is `@GetMapping(path = TimeDepositQueriesApi.PATH_GET_ALL_TIME_DEPOSITS, produces = "application/json")`
    returning `ResponseEntity<StreamingResponseBody>`;
  - the body writes `[`, then each `TimeDepositDtoMapper.toDto(details)` through a Jackson generator from the
    application's `ObjectMapper`, then `]`;
  - the service call happens **inside** the streaming callback.
  - The comment explains why `GET` does not implement the generated interface: it returns a `List`, which cannot
    stream.
- Tests: `JpaTimeDepositRepositoryIT` and `TimeDepositApiIT`, plus updating the `TimeDepositServiceTest` fake.

- [x] **Step 1: Write the tests.**
  - `JpaTimeDepositRepositoryIT.forEachWithWithdrawalsGroupsRowsPerDepositInIdOrder`: this replaces the old
    `findAllWithWithdrawals…` test with the same expectations (ids 1–6, deposit 2 with 1 withdrawal, deposit 3 with 2
    in id order, the rest empty). It also checks that the last deposit, 6, has no withdrawals and is still emitted.
  - `JpaTimeDepositRepositoryIT.forEachWithWithdrawalsEmitsTheLastDepositWithItsWithdrawals`: insert deposit 7 with 2
    withdrawals. It is emitted last, with both withdrawals.
  - `TimeDepositApiIT`: all existing `GET` assertions stay as they are, including the ISO date and the
    `"{GET [/time-deposits], produces [application/json]}"` mapping check.
  - New `TimeDepositApiIT.getStreamsLargeDataSets`:
    - `insert into time_deposits (id, plan_type, days, balance) select g, 'basic', 31, 100.00 from generate_series(1000, 5999) g`;
    - `GET` returns 5,006 deposits with ids in ascending order.
  - `TimeDepositServiceTest.forEachTimeDepositPassesEveryDepositToTheConsumer`.
- [x] **Step 2: Run `mvn verify`.** Expected: FAIL.
- [x] **Step 3: Implement** the files listed above.
- [x] **Step 4: Run `mvn verify`.** Expected: PASS. `applicationExposesExactlyTwoApiOperations` is green.

### Task 5: Documentation
**Files:** `README.md`, `docs/api.md`, `CLAUDE.md` (add `domain.service.interest` to the package tree), and
`openapi.yaml` (`POST` description).

- [x] **Step 1: Update `README.md`.**
  - "Architecture": the strategies and how to add a plan.
  - "Assumptions": the schema additions (`interest_runs`, `last_interest_run_id`), the run semantics (resume,
    overlapping calls join, `204` = run complete), and the streaming trade-offs (one connection held while the client
    reads; a failure mid-stream truncates a `200`).
  - Replace "Next step" with what is now done.
- [x] **Step 2: Update `docs/api.md`:** the `POST` semantics (batches, resume, overlap) and the streaming note.
- [x] **Step 3: Update the `openapi.yaml` `POST` description:** resumable and idempotent per run; overlapping calls
  join the open run.
- [x] **Step 4: Run `mvn verify`.** Expected: PASS. Manual smoke test: `mvn spring-boot:run`, then `GET` (6 deposits)
  and `POST` (`204`); stop the app and run `docker compose down -v`.
