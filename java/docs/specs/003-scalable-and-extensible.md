# 003 – Extensible interest rules and scalable endpoints

## Intent
A bank's deposit book grows, and so do its products. The service must keep working as the number of deposits grows,
and the brief asks that the design be "extensible to accommodate future complexities in interest calculations". This
spec covers both kinds of scalability, without breaking the hexagonal structure in `CLAUDE.md`:

1. **Design scalability.** Adding a plan type, or changing one plan's rule, must not require editing
   `TimeDepositCalculator` (Open/Closed).
2. **Data scalability of `POST /time-deposits/balance-updates`.**
   - Process deposits in bounded batches, with short transactions.
   - Make the run **idempotent and resumable**: after a crash, a retry finishes the interrupted month instead of
     paying it twice.
3. **Data scalability of `GET /time-deposits`.** Stream the full list with constant server memory. The response stays
   exactly the contract from spec 002: one JSON array of all deposits (developer's choice: no pagination).

> **Amendment (developer decision, after implementation):** the streaming `GET` (section 3) was reverted as
> over-engineering for this service. `GET /time-deposits` again implements the generated `TimeDepositsApi` and returns
> `ResponseEntity<List<TimeDepositDto>>`. What remains from section 3 is the single-query listing (one SQL join,
> grouped in the persistence adapter, no N+1). Goal 3, anti-goal "loading all deposits into memory" (for `GET`) and
> acceptance criterion 4 no longer apply; the tag split in `openapi.yaml` was reverted.

## Goals
- One interest strategy per plan type. `TimeDepositCalculator.updateBalance(List<TimeDeposit>)` keeps its signature
  and **exact** behaviour, and the 146 characterization tests pass unchanged.
- `POST` memory and lock time are bounded by the batch size, not by the number of deposits.
- A `POST` that fails part-way and is then retried leaves every deposit with exactly one more month.
- `GET` memory is bounded independent of the number of deposits and withdrawals.
- Still exactly two API operations. The dependency rules in `CLAUDE.md` still hold (`ArchitectureTest` green).

## Non-goals
- Pagination or filtering on `GET` (rejected by the developer).
- Asynchronous `POST` (`202` plus polling). `POST` stays synchronous and returns `204` when the run is complete.
- Distributed scheduling or fencing tokens. Database locks are sufficient (see "Concurrency").
- Changing the interest rules, the rounding, or the 30/360 ageing from spec 002.

## Anti-goals
- Any change to the characterization test files, or to `updateBalance`'s signature or results.
- Interest logic outside `domain`, or framework imports in `domain`.
- A month applied twice to a deposit within one run, or a month silently skipped.
- Loading all deposits into memory on either endpoint.

## Approaches considered
| Topic | Options | Decision |
|---|---|---|
| Extensibility | (a) Strategy per plan behind the unchanged calculator; (b) rules engine / configuration-driven rates; (c) keep the `switch` | **(a)**. Each plan's rule is code with its own tests. (b) is over-engineering for three plans. |
| `POST` at scale | (a) Batches + run tracking (resumable); (b) plain batches, accepting the partial-failure risk; (c) a single SQL `UPDATE` | **(a)**, chosen by the developer. (c) would move interest logic into SQL, out of the domain. |
| `GET` at scale | (a) Stream the full list; (b) cursor pagination; (c) both | **(a)**, chosen by the developer |

## Design

### 1. Interest strategies (`domain.service.interest`, framework-free)
- `InterestStrategy`:
  - `String planType()`: the plan value, e.g. `"basic"`. Strategies are keyed by the **string**, not the
    `PlanType` enum, so a new plan can be added without editing the enum (design decision; see below).
  - `double monthlyInterest(TimeDeposit deposit)`: the plan-specific eligibility plus `balance * annualRate / 12`.
    It must keep exactly this evaluation order, because other orders round differently (spec 001).
- Implementations:

  | Strategy | Rule |
  |---|---|
  | `BasicInterestStrategy` | always `balance * rate / 12` |
  | `StudentInterestStrategy` | `days <= 365` |
  | `PremiumInterestStrategy` | `days > 45` |

  The rate comes from `PlanType.getAnnualInterestRate()`.
- `TimeDepositCalculator`:
  - A no-argument constructor uses the three default strategies, so existing callers and tests are unchanged.
  - A new constructor `TimeDepositCalculator(List<InterestStrategy>)` lets callers register further strategies.
  - The calculator keeps:
    - the cross-plan rule (no interest while `days <= 30`);
    - the plan lookup (unknown plan → 0, and the `null` plan behaviour pinned by spec 001);
    - the HALF_UP rounding;
    - setting the balance on every deposit.
- Adding a plan therefore means writing a new strategy class and registering it. Neither the calculator nor
  `PlanType` is edited. `PlanType` remains the catalogue of the built-in plans and their rates, which the default
  strategies use.
- **Lookup (design decision):** the calculator holds an immutable `Map<String, InterestStrategy>` built with
  `Map.copyOf`.
  - An unknown plan → no strategy → 0 interest.
  - A `null` plan with `days > 30` must still throw `NullPointerException`, as pinned by spec 001. It is enforced
    with an explicit `Objects.requireNonNull` and a comment, not left to the map's behaviour.
  - Duplicate plan values in the strategy list are rejected at construction.
- `DomainConfiguration` builds the calculator bean from the strategies.

### 2. Resumable batched balance update
**Schema, Flyway `V3__interest_runs.sql`.** These are additions only; no brief column changes.
- `interest_runs`:
  - `id integer generated always as identity primary key`
  - `started_at timestamptz not null default now()`
  - `completed_at timestamptz null`
- A partial unique index allows **at most one open run**:
  `create unique index interest_runs_one_open on interest_runs ((completed_at is null)) where completed_at is null`.
- `time_deposits.last_interest_run_id integer null references interest_runs(id)`.
- Documented in the README as an addition to the brief's schema, needed for idempotency.

**Ports (`repository.api`):** a new `InterestRunRepository`, plus additions to `TimeDepositRepository`.
- `int startOrResumeRun()` creates the open run, or returns the existing open one. It is race-free: an insert that
  conflicts with the partial unique index falls back to selecting the open run.
- `List<TimeDeposit> findNextBatchForUpdate(int runId, int afterId, int batchSize)`:
  - `WHERE id > :afterId AND coalesce(last_interest_run_id, 0) < :runId AND <run :runId is still open> ORDER BY id LIMIT :batchSize`
  - Run ids only increase, so rows stamped by a newer run never qualify, and nothing qualifies once the run is
    completed. A slow worker still holding an old run id therefore cannot pay a month twice (found in code review).
  - `FOR UPDATE SKIP LOCKED`
- `void saveBatch(List<TimeDeposit> deposits, int runId)` persists `balance` and `days` and sets
  `last_interest_run_id = runId`.
- `boolean completeRunIfDone(int runId)` sets `completed_at` only if no deposit still has
  `coalesce(last_interest_run_id, 0) < runId`.
- The old `findAllForUpdate` and `saveAll` are removed.

**Business (`TimeDepositService.updateAllBalances`):**
1. `runId = startOrResumeRun()`.
2. Loop, **one transaction per batch**:
   - fetch the next batch after the last id seen;
   - stop if it's empty;
   - `calculator.updateBalance(batch)` → `depositAgeing.advanceOneMonth(batch)` → `saveBatch(batch, runId)`.
3. `completeRunIfDone(runId)`. If it returns false, another worker still holds some of the run's rows. The loop
   rescans from `afterId = 0` after a short backoff (e.g. 100 ms), until `completeRunIfDone` returns true
   (design decision).
   - So `204` always means **the run is complete**.
   - If the other worker's batch rolls back, the rescan picks those rows up.
   - Liveness relies on the other worker's transaction ending, which the database guarantees on commit, rollback
     or disconnect. No timeout or error status is added: error handling is out of scope, and `openapi.yaml` has no
     other response.

- Batch size: property `time-deposits.balance-update.batch-size`, default `1000`.
- Per-batch transactions use Spring's `TransactionOperations` in `business`. This is allowed by the dependency rules:
  only `domain` must be framework-free.

**Semantics:**
- **Crash or exception mid-run:** batches already committed keep their month. The open run stays open, and the next
  `POST` resumes it, processing only the deposits not yet in the run. Every deposit ends with exactly one month.
- **Sequential `POST`s:** each completed run → the next `POST` starts a new run, i.e. one more month (unchanged from
  spec 002).
- **Overlapping `POST`s:** **behaviour change from spec 002.** A `POST` that arrives while a run is open **joins**
  that run instead of starting a second month.
  - The workers split the remaining batches, because `SKIP LOCKED` lets each skip rows the other has locked. Both
    return `204` once the run is complete.
  - So a double-click or a client retry during a run pays one month, not two.
  - Spec 002's test `parallelUpdatesApplyTwoMonths` is replaced accordingly.
- **Locks** are held per batch only, never across the whole table.

### 3. Streaming `GET`
- **Port:** `TimeDepositRepository.forEachWithWithdrawals(Consumer<TimeDepositDetails> consumer)`.
  - Deposits are ordered by id, each with its withdrawals ordered by id.
  - It is implemented in `repository.impl` with a JDBC cursor (`JdbcClient` / `JdbcTemplate` with a fetch size,
    inside a read-only transaction). One left-join query, ordered by deposit id and then withdrawal id.
  - Consecutive rows are grouped into one `TimeDepositDetails` before calling the consumer.
  - Constant memory, no N+1 queries.
- **Business:** `TimeDepositService.forEachTimeDeposit(Consumer<TimeDepositDetails>)` is
  `@Transactional(readOnly = true)` and delegates to the port. `getAll()` and `findAllWithWithdrawals()` are removed.
- **REST:** the controller writes the JSON array incrementally, mapping each item to `TimeDepositDto` with the
  existing mapper and writing it with Jackson's streaming generator.
  - The transaction runs inside the streaming callback, so it is open while rows are written.
  - The response body, status and content type stay exactly as in `openapi.yaml`. Because the generated interface
    returns `ResponseEntity<List<TimeDepositDto>>`, which cannot stream, the generator is configured so the
    controller can return a streaming body while the contract stays the single source of truth for path, schema and
    DTOs. How to configure the generator for that is an implementation detail. `TimeDepositApiIT` still verifies the
    response against the contract.
- `openapi.yaml` is unchanged, apart from description text that mentions streaming.

### Concurrency (summary)
| Situation | Mechanism | Outcome |
|---|---|---|
| Two `POST`s overlap | partial unique index (one open run) + `SKIP LOCKED` + `last_interest_run_id` filter | one month, work split between them |
| `POST` crashes mid-run | open run + per-batch commits | the next `POST` resumes; exactly one month per deposit |
| `GET` during a `POST` | MVCC snapshot reads | consistent rows; may show a mix of already-updated and not-yet-updated batches |

## Testing
- **Characterization tests (spec 001):** unchanged and green. They prove the strategy refactor keeps behaviour.
- **Unit (domain):**
  - each strategy's eligibility boundaries and rate;
  - `TimeDepositCalculator(List<InterestStrategy>)` with an extra custom strategy for a new plan (e.g. `"gold"`):
    that plan earns interest without editing the calculator or `PlanType`. The production calculator bean still has
    only the three built-in strategies, so `TimeDepositApiIT.unknownPlanTypeIsListedAndEarnsNoInterest` stays valid.
- **Unit (business):** `TimeDepositService` with in-memory fakes:
  - batching visits every deposit exactly once across batches;
  - a resumed run skips deposits already in it;
  - `completeRunIfDone` is called after the loop.
- **Integration (Testcontainers):**
  - With batch size 2 over the 6 seeded deposits, one `POST` gives the same results as spec 002 (balances
    `1201.00, 401.00, 241.00, 1000.00, 400.00, 240.00`; days `75, 130, 90, 50, 430, 70`).
  - **Resume:** a test-only failing hook makes the 2nd batch throw. After the failure:
    - deposits 1–2 are updated and 3–6 are not;
    - the run is still open;
    - a second `POST` completes the run;
    - every deposit has exactly one month (the same values as above).
  - **Overlap:** two concurrent `POST`s give exactly one month in total. Both return `204` only after
    `interest_runs.completed_at` is set.
  - **Sequential:** two `POST`s after each other give two months (spec 002's "after update 2" values).
  - **Streaming `GET`:** same JSON as spec 002 for the seed data. With 5,000 extra generated deposits, it returns all
    5,006 in id order. The repository test checks that deposits are grouped correctly, including those without
    withdrawals and the last deposit in the result.
  - `applicationExposesExactlyTwoApiOperations` stays green.
- `ArchitectureTest` gains: `domain.service.interest` has no framework imports. This is covered by the existing
  domain rule; the new package must not escape it.

## Acceptance criteria
1. A new plan type can be added by adding a `PlanType` constant and an `InterestStrategy`, without editing
   `TimeDepositCalculator`. This is demonstrated by a unit test.
2. The 146 characterization tests pass unchanged.
3. `POST` processes deposits in batches of the configured size, holds locks only per batch, and is resumable and
   idempotent per run, as described in "Semantics".
4. `GET` streams the complete array with memory independent of data size, and its response matches spec 002's
   contract.
5. Still exactly two API operations. The hexagonal dependency rules hold.
6. README, `docs/api.md` and `openapi.yaml` descriptions explain the run semantics and the schema addition.
7. `mvn verify` is green.
