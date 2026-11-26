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
├── business         TimeDepositService: the use cases (list, update all) and the transaction boundary
├── repository
│   ├── api          TimeDepositRepository: the persistence port
│   └── impl         JPA adapter, entities and Spring Data repository
├── rest
│   ├── controller   TimeDepositController, implementing the interface generated from openapi.yaml
│   └── dto          generated API models and the domain → DTO mapper
└── application      Spring Boot bootstrap and bean wiring
```

- **Contract-first API.** `src/main/resources/static/openapi.yaml` is the source of truth. The interface and models
  are generated at build time, and Swagger UI serves the same file.
- **Schema.** Flyway migrations in `src/main/resources/db/migration` create the tables and load the sample data.
- **Update flow.** In one transaction, `POST` does: lock and load all deposits (`SELECT … FOR UPDATE`) →
  `TimeDepositCalculator.updateBalance` → `DepositAgeing.advanceOneMonth` → save.

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
  - Each `POST` therefore applies one more month and is not idempotent.
- **Concurrent updates are serialized.** A row lock makes an overlapping update wait and then build on the first
  one's result, so no month of interest is lost.
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
| Unit | Use cases with an in-memory fake repository; `DepositAgeing`; `Money`; DTO mapping; architecture rules | `mvn test` |
| Integration (`*IT`) | PostgreSQL Testcontainer: migrations, the persistence adapter, the row lock under concurrency, both endpoints end to end, Swagger | `mvn verify` |

## Project documentation
- [docs/api.md](docs/api.md): how to run the service and trigger the endpoints via Swagger.
- [docs/specs/](docs/specs) and [docs/plans/](docs/plans): the spec and plan behind each feature.
- [docs/ai-assisted-development.md](docs/ai-assisted-development.md): the AI tools, setup, rules, and which parts
  were AI-assisted.
- [CLAUDE.md](CLAUDE.md) and [.claude/settings.json](.claude/settings.json): the agent configuration used throughout.

## Next step
The interest rules for each plan type still live in one class. The planned next step is one interest strategy per
plan type, so new plans or rules can be added without editing the calculator. The characterization suite makes that
refactor safe.
