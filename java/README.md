# XA Bank Time Deposit – Java solution

A Spring Boot service built from the kata's starter domain code (brief: [`../README.md`](../README.md)). It stores
time deposits and their withdrawals in PostgreSQL. It exposes **exactly two** REST operations, defined contract-first
in OpenAPI.

| Operation | Endpoint |
|---|---|
| List all time deposits (`id`, `planType`, `balance`, `days`, `withdrawals`) | `GET /time-deposits` |
| Update the balances of all time deposits | `POST /time-deposits/balance-updates` |

## Quick start
Requires JDK 17+, Maven and a running Docker.

```bash
cd java
mvn spring-boot:run      # starts PostgreSQL from compose.yaml, migrates and seeds it
```

Open **Swagger UI** at <http://localhost:8080/swagger-ui/index.html>. Step-by-step instructions, the sample data and
the expected results are in **[docs/api.md](docs/api.md)**.

```bash
mvn verify               # unit tests + integration tests against a PostgreSQL Testcontainer
```

## Architecture
The code follows a hexagonal layout. The domain is framework-free, and adapters depend on ports, never the reverse.
`ArchitectureTest` enforces these rules.

```
org.ikigaidigital
├── domain
│   ├── model        TimeDeposit, PlanType, Withdrawal, TimeDepositDetails, Money
│   └── service      TimeDepositCalculator (interest), DepositAgeing (one month passes)
│       └── interest InterestStrategy per plan: Basic, Student, Premium
├── business         TimeDepositService: the use cases (list all, update all as a resumable run)
├── repository
│   ├── api          TimeDepositRepository, InterestRunRepository: the persistence ports
│   └── impl         JPA/JDBC adapters: batch queries, run tracking, single-query listing
├── rest
│   ├── controller   TimeDepositController, implementing the interface generated from openapi.yaml
│   └── dto          generated API models and the domain → DTO mapper
└── application      Spring Boot main class
    └── config       bean wiring and configuration properties
```

- **Contract-first API.** `src/main/resources/static/openapi.yaml` is the source of truth. The interface and models
  are generated at build time, and Swagger UI serves the same file.
- **Schema.** Flyway migrations in `src/main/resources/db/migration` create the tables and load the sample data.
- **Extensible interest rules.** Each plan's rule is an `InterestStrategy`. To add a plan, write one strategy class
  and register it as a bean in `DomainConfiguration`. Neither `TimeDepositCalculator` nor `PlanType` changes.
  `TimeDepositCalculatorExtensionTest` shows this with a made-up `"gold"` plan.
- **Update flow (`POST`).** An interest run, processed in batches (default 1000,
  `time-deposits.balance-update.batch-size`). Each batch is one short transaction:
  1. lock the next batch (`SELECT … FOR UPDATE SKIP LOCKED`);
  2. `TimeDepositCalculator.updateBalance`;
  3. `DepositAgeing.advanceOneMonth`;
  4. save, marking each deposit with the run id.

  The run is completed once every deposit carries its id.
- **List flow (`GET`).** One SQL query joins deposits and withdrawals (no N+1 queries), and the rows are grouped
  into deposits in the persistence adapter.

## Assumptions and decisions
These are also justified in code comments where they apply.

- **The interest rules are unchanged.** `TimeDepositCalculator.updateBalance(List<TimeDeposit>)` keeps its exact
  signature and behaviour, pinned by 146 characterization tests (`docs/specs/001-…`).
- **Table names are snake_case.** The brief's `timeDeposits` and `timeDepositId` become `time_deposits` and
  `time_deposit_id`, because PostgreSQL folds unquoted identifiers to lower case. See `V1__create_tables.sql`.
- **Each update is one month passing.**
  - Interest is calculated with the deposit's current `days`. Then every deposit ages by **30 days**, following the
    standard 30/360 day-count convention.
  - Calendar months are not possible because the brief's tables store no dates.
  - Each completed `POST` applies one more month.
- **Updates are idempotent per run and resumable.**
  - **Crash or error mid-run:** batches already committed keep their month. The run stays open, and the next `POST`
    finishes it, processing only the remaining deposits. No deposit is paid twice.
  - **Overlapping `POST`s** join the open run and split its batches, so the month is applied **once**, not once per
    call.
  - A `POST` sent after a run has completed starts the next month, by design: each completed call is one month.
  - Run ids only increase, and a worker only processes deposits whose last run is older than its own, open run. A
    slow worker that still holds an already-completed run id therefore cannot pay anything again.
  - `204` is returned only when the run is complete. If another call still holds some deposits, the call waits
    briefly and rescans; this also picks up the rows if the other call fails.
  - **Schema addition:** this needs an `interest_runs` table and a `time_deposits.last_interest_run_id` column (Flyway
    `V3`). They are additions; no column from the brief changes. A partial unique index allows only one open run.
- **Safe with multiple instances.** All coordination happens in PostgreSQL: the one-open-run index, `SKIP LOCKED`
  batches and the run stamps. Several pods can serve `POST` at the same time, and an instance that crashes mid-batch
  only rolls back that batch. The monthly trigger should still come from one place (e.g. a Kubernetes `CronJob`), so
  that instances don't each start a new month after a run completes.
- **Money.**
  - The domain keeps `TimeDeposit`'s `Double` balance.
  - The API and the database use two-decimal amounts (`numeric(19,2)`), converted in one place: `Money.toCents`.
- **Withdrawals are history.** They are returned with each deposit, but they do not change balances or interest.
- **Unknown plan types** earn no interest and do not cause an error, as in the original calculator.
- **Changes to `TimeDeposit`.**
  - It moved to `domain.model` as part of the hexagonal restructuring. Nothing outside this repository imports it.
  - It gained an additive `setDays(int)`, needed for deposit ageing. The constructor, getters and `setBalance` are
    unchanged.
- **Out of scope, per the brief:** input validation and error handling.
- **Generated documentation endpoints.** springdoc's generated `/v3/api-docs` remains reachable because Swagger UI
  needs it. The service's own API is still exactly two operations, which `TimeDepositApiIT` verifies.

## Tests
| Kind | What | Run by |
|---|---|---|
| Characterization | Every rule, boundary and numerical quirk of the original calculator | `mvn test` |
| Unit | Interest strategies and adding a new plan; use cases with an in-memory fake (batching, crash and resume, rescan); `DepositAgeing`; `Money`; DTO mapping; architecture rules | `mvn test` |
| Integration (`*IT`) | PostgreSQL Testcontainer, batch size 2: migrations, run tracking (one open run under concurrent callers), `SKIP LOCKED` batches, resuming an interrupted run, overlapping updates paying one month, listing 5,000+ deposits, both endpoints end to end, Swagger | `mvn verify` |

## Project documentation
- [docs/api.md](docs/api.md): how to run the service and trigger the endpoints via Swagger.
- [docs/specs/](docs/specs) and [docs/plans/](docs/plans): the spec and plan behind each feature.
- [docs/ai-assisted-development.md](docs/ai-assisted-development.md): the AI tools, setup, rules, and which parts
  were AI-assisted.
- [CLAUDE.md](CLAUDE.md) and [.claude/settings.json](.claude/settings.json): the agent configuration used throughout.
