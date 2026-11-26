# 002 – REST API and PostgreSQL Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Run the time deposit domain as a Spring Boot service with PostgreSQL persistence and exactly two REST operations. The operations are defined contract-first in `openapi.yaml`.

**Architecture:** Packages follow the `CLAUDE.md` structure:
- `domain` is unchanged, plus two new records.
- `business.TimeDepositService` orchestrates load-with-lock → `TimeDepositCalculator` → save.
- `repository.api` is the port and `repository.impl` its JPA adapter.
- `rest` implements interfaces generated from `openapi.yaml`.
- `application` holds the bootstrap and wiring.

Flyway owns the schema and the seed data. Testcontainers Postgres backs every integration test.

**Tech Stack:** Java 17, Spring Boot 4.2.0 (Web MVC, Data JPA, Flyway, Docker Compose support), PostgreSQL 17, openapi-generator-maven-plugin 7.27.0, springdoc-openapi 3.2.1, JUnit/AssertJ via `spring-boot-starter-test`, Testcontainers (Boot-managed).

**Spec:** `docs/specs/002-rest-api-and-persistence.md`

## Global Constraints
- **Domain stays unchanged.** Don't change `TimeDeposit` (constructor, getters, `setBalance`) or `TimeDepositCalculator.updateBalance`. The spec-001 test files stay unmodified, and all 146 tests stay green.
- **No framework code in the domain.** No Spring, JPA, Jakarta or Jackson imports in `org.ikigaidigital.domain..`.
- **Dependency direction.** `rest` and `repository.impl` depend on `business` / `repository.api`, never the reverse. `business` never imports `repository.impl` or `rest`.
- **Exactly two API operations:** `GET /time-deposits` (`getAllTimeDeposits`) and `POST /time-deposits/balance-updates` (`updateAllBalances`, `204`). No Actuator.
- **Versions:** Java 17 source/target via `<java.version>17</java.version>`. Spring Boot parent `4.2.0`, springdoc `3.2.1`, openapi-generator `7.27.0`. Every other version is Boot-managed. Docker image `postgres:17-alpine` everywhere.
- **Interest** is computed only by `TimeDepositCalculator`.
- **Money mapping:**
  - DB/API → domain: `BigDecimal.doubleValue()`.
  - Domain → DB/API: `BigDecimal.valueOf(double).setScale(2, RoundingMode.HALF_UP)`.
- **No validation or error handling.** No Mockito: use hand-written fakes.
- **Test naming:** integration tests are named `*IT` and run under failsafe in `mvn verify`. Unit tests are `*Test` and run under surefire.
- **Commits:** each task ends with a commit step. Run it only if the user has approved per-task commits; otherwise leave the changes uncommitted.

## Review Focus
1. **Empty database:** `GET` returns `[]` and `POST` returns `204` without error. Pinned in Task 4, `TimeDepositApiIT#emptyDatabase…`.
2. **Unknown plan type stored in the DB** (e.g. `"gold"`): `GET` shows it, and `POST` leaves its balance unchanged (no exception). Pinned in Task 4.
3. **Withdrawal `date` JSON format:** it must serialize as `"2026-01-15"`, not as an array or a timestamp. Pinned in Task 4 with a raw-JSON assertion.
4. **`double` noise reaching the DB:** a domain balance of `0.1 + 0.2` must persist as `0.30`. Pinned in Task 3.
5. **Overlapping updates:** a second update must wait for a held row lock rather than read stale balances. Pinned in Task 3 (lock-holder test and parallel-calls test).

---

### Task 1: Spring Boot build and application skeleton

**Files:**
- Modify: `pom.xml`
  - Set the parent to `org.springframework.boot:spring-boot-starter-parent:4.2.0` and `<java.version>17</java.version>`.
  - Remove the explicit `junit-jupiter-engine`, `junit-jupiter-params` and `assertj-core` dependencies, the surefire `3.0.0-M3` entry and the compiler-plugin `source`/`target` block.
  - Add the main dependencies: Web MVC starter, Data JPA starter, Flyway starter + `flyway-database-postgresql`, `org.postgresql:postgresql` (runtime), `spring-boot-docker-compose` (optional, runtime), `springdoc-openapi-starter-webmvc-ui:3.2.1`.
  - Add the test dependencies: `spring-boot-starter-test`, `spring-boot-testcontainers`, Testcontainers PostgreSQL module.
  - Add the build plugins: `spring-boot-maven-plugin`, `maven-failsafe-plugin` (goals `integration-test` and `verify`).
  - Use the Boot 4.2 artifact names. Boot 4 split some starters, so if one fails to resolve, check the Boot 4.2 reference for the module name.
- Create: `src/main/java/org/ikigaidigital/application/TimeDepositApplication.java`
- Create: `src/main/java/org/ikigaidigital/application/DomainConfiguration.java`
- Create: `src/main/resources/application.yaml`
- Create: `compose.yaml` (service `postgres`, image `postgres:17-alpine`, db/user/password `timedeposits`, port 5432)
- Create: `src/test/java/org/ikigaidigital/application/TestcontainersConfiguration.java`
- Create: `src/test/java/org/ikigaidigital/application/PostgresIntegrationTest.java`
- Create: `src/test/java/org/ikigaidigital/application/ApplicationContextIT.java`
- Create: `src/test/java/org/ikigaidigital/ArchitectureTest.java`

**Interfaces:**
- Produces:
  - `TimeDepositApplication`: `@SpringBootApplication(scanBasePackages = "org.ikigaidigital")`, with `@EntityScan` and `@EnableJpaRepositories` pointed at `org.ikigaidigital.repository.impl`.
  - `DomainConfiguration`: `@Bean TimeDepositCalculator timeDepositCalculator()`.
  - `TestcontainersConfiguration`: `@TestConfiguration(proxyBeanMethods = false)` with `@Bean @ServiceConnection PostgreSQLContainer postgres()` using `postgres:17-alpine`.
  - `abstract class PostgresIntegrationTest`:
    - annotated `@SpringBootTest(webEnvironment = RANDOM_PORT, properties = "spring.flyway.clean-disabled=false")` and `@Import(TestcontainersConfiguration.class)`;
    - exposes `@LocalServerPort protected int port`;
    - has `@BeforeEach void resetDatabase()`, which runs `flyway.clean(); flyway.migrate();` so every IT starts from the seed data.

- [x] **Step 1: Write `ArchitectureTest` (plain JUnit, no new dependencies).** It walks `src/main/java/org/ikigaidigital` and checks the import lines of each file:
  - `domainHasNoFrameworkImports`: no file under `domain/` imports `org.springframework`, `jakarta.`, `com.fasterxml` or `tools.jackson`.
  - `businessDoesNotDependOnAdapters`: no file under `business/` imports `org.ikigaidigital.repository.impl` or `org.ikigaidigital.rest`.
  - `restDoesNotDependOnPersistence`: no file under `rest/` imports `org.ikigaidigital.repository`.
- [x] **Step 2: Write `ApplicationContextIT extends PostgresIntegrationTest`** with `contextLoadsWithCalculatorBean()`: `assertThat(context.getBean(TimeDepositCalculator.class)).isNotNull()`.
- [x] **Step 3: Run `mvn verify`.** Expected: FAIL, because the Boot classes and dependencies are missing.
- [x] **Step 4: Implement** the pom changes, `TimeDepositApplication`, `DomainConfiguration`, `application.yaml` (`spring.jpa.hibernate.ddl-auto: validate`, `spring.jpa.open-in-view: false`) and `compose.yaml`.
- [x] **Step 5: Run `mvn verify`.** Expected: PASS. The 146 spec-001 tests, `ArchitectureTest` and `ApplicationContextIT` pass, and failsafe reports the IT.
- [x] **Step 6: Commit:** `build: migrate to Spring Boot 4.2 with Postgres test infrastructure`.

### Task 2: Domain records, repository port and business service

**Files:**
- Create: `src/main/java/org/ikigaidigital/domain/model/Withdrawal.java`
- Create: `src/main/java/org/ikigaidigital/domain/model/TimeDepositDetails.java`
- Create: `src/main/java/org/ikigaidigital/repository/api/TimeDepositRepository.java`
- Create: `src/main/java/org/ikigaidigital/business/TimeDepositService.java`
- Test: `src/test/java/org/ikigaidigital/business/TimeDepositServiceTest.java`
- Test: `src/test/java/org/ikigaidigital/business/InMemoryTimeDepositRepository.java` (test fake)

**Interfaces:**
- Consumes: `TimeDepositCalculator` bean (Task 1).
- Produces:
  - `record Withdrawal(int id, BigDecimal amount, LocalDate date)`
  - `record TimeDepositDetails(TimeDeposit deposit, List<Withdrawal> withdrawals)`
  - `interface TimeDepositRepository` with:
    - `List<TimeDepositDetails> findAllWithWithdrawals()`
    - `List<TimeDeposit> findAllForUpdate()`
    - `void saveBalances(List<TimeDeposit> deposits)`
  - `@Service class TimeDepositService(TimeDepositRepository, TimeDepositCalculator)` with:
    - `List<TimeDepositDetails> getAll()`
    - `@Transactional void updateAllBalances()`

- [x] **Step 1: Write `TimeDepositServiceTest`** against the fake. The fake stores deposits by id and records what `saveBalances` received.
  - `updateAllBalancesSavesTheCalculatedBalancesOfAllDeposits`:
    - Input: basic 1200.00 day 45, premium 240.00 day 40, student 400.00 day 100.
    - Expected saved balances, in that order: `1201.00`, `240.00`, `401.00`.
    - `saveBalances` is called once, with all three deposits.
  - `updateAllBalancesReadsDepositsThroughTheLockingQuery`: the fake counts `findAllForUpdate` calls → `1`.
  - `getAllReturnsTheDetailsFromTheRepository`: `getAll()` `isEqualTo` the fake's `findAllWithWithdrawals()` result (one deposit with two withdrawals).
- [x] **Step 2: Run `mvn test -Dtest=TimeDepositServiceTest`.** Expected: FAIL (compilation: types missing).
- [x] **Step 3: Implement** the two records, the port and `TimeDepositService`. `updateAllBalances` is `findAllForUpdate` → `calculator.updateBalance(list)` → `saveBalances(list)`.
- [x] **Step 4: Run `mvn test`.** Expected: PASS, and `ArchitectureTest` is still green.
- [x] **Step 5: Commit:** `feat: add time deposit use cases behind a repository port`.

### Task 3: PostgreSQL schema, seed data and JPA adapter

**Files:**
- Create: `src/main/resources/db/migration/V1__create_tables.sql`. Tables exactly as in the spec's "Database" section. A header comment states the snake_case assumption for `timeDeposits` and `timeDepositId`.
- Create: `src/main/resources/db/migration/V2__seed_data.sql`
- Create: `src/main/java/org/ikigaidigital/repository/impl/TimeDepositEntity.java`
- Create: `src/main/java/org/ikigaidigital/repository/impl/WithdrawalEntity.java`
- Create: `src/main/java/org/ikigaidigital/repository/impl/TimeDepositJpaRepository.java`
- Create: `src/main/java/org/ikigaidigital/repository/impl/JpaTimeDepositRepository.java`
- Test: `src/test/java/org/ikigaidigital/repository/impl/JpaTimeDepositRepositoryIT.java`
- Test: `src/test/java/org/ikigaidigital/business/TimeDepositServiceConcurrencyIT.java`

**Seed data (`V2`).** Explicit ids, then reset both identity sequences with `setval(pg_get_serial_sequence(...), max(id))`.

| id | plan | balance | days | withdrawals (id, amount, date) | after one update |
|---|---|---|---|---|---|
| 1 | basic | 1200.00 | 45 | none | 1201.00 |
| 2 | student | 400.00 | 100 | (1, 50.00, 2026-01-15) | 401.00 |
| 3 | premium | 240.00 | 60 | (2, 100.00, 2026-02-01), (3, 25.50, 2026-03-10) | 241.00 |
| 4 | basic | 1000.00 | 20 | none | 1000.00 |
| 5 | student | 400.00 | 400 | none | 400.00 |
| 6 | premium | 240.00 | 40 | none | 240.00 |

**Interfaces:**
- Consumes: `TimeDepositRepository` (Task 2), `PostgresIntegrationTest` (Task 1).
- Produces:
  - `@Repository class JpaTimeDepositRepository implements TimeDepositRepository`.
  - `TimeDepositJpaRepository extends JpaRepository<TimeDepositEntity, Integer>` with:
    - `findAllWithWithdrawalsOrdered()`: a fetch join or `@EntityGraph`, ordered by deposit id and then withdrawal id;
    - `@Lock(PESSIMISTIC_WRITE) findAllByOrderByIdAsc()`.

- [x] **Step 1: Write `JpaTimeDepositRepositoryIT extends PostgresIntegrationTest`.**
  - `findAllWithWithdrawalsReturnsSeededDepositsInIdOrder`:
    - ids `1..6`;
    - plan types, days and balances as in the table;
    - deposit 3 has withdrawals `[2, 3]` with amounts `100.00` and `25.50` and dates `2026-02-01` and `2026-03-10`;
    - deposits 1 and 4–6 have empty withdrawal lists.
  - `saveBalancesPersistsTwoDecimalValues`:
    - load via `findAllForUpdate` inside a `TransactionTemplate`, set deposit 1's balance to `0.1 + 0.2`, call `saveBalances`;
    - a `JdbcTemplate` read of `balance` for id 1 `isEqualByComparingTo("0.30")`.
  - `findAllForUpdateBlocksConcurrentUpdatesUntilTheLockIsReleased`:
    - thread A, inside a `TransactionTemplate`, calls `findAllForUpdate()` and then waits on a latch;
    - meanwhile, thread B calls `TimeDepositService.updateAllBalances()`;
    - assert B has **not** finished after 500 ms, release A, and assert B finishes within 5 s;
    - deposit 1 is then `1201.00`.
- [x] **Step 2: Write `TimeDepositServiceConcurrencyIT extends PostgresIntegrationTest`.**
  - `parallelUpdatesApplyTwoMonths`: two threads start `updateAllBalances()` together (start latch) and both complete.
  - Expected DB balances, equal to two sequential updates:
    - deposits 1, 2, 3 → `1202.00`, `402.00`, `242.00`, because the second month's interest is 1201/1200 → `1.00`, 401·3%/12 → `1.00` and 241·5%/12 → `1.00`;
    - deposits 4, 5, 6 unchanged.
- [x] **Step 3: Run `mvn verify -Dit.test='JpaTimeDepositRepositoryIT,TimeDepositServiceConcurrencyIT'`.** Expected: FAIL (no migrations, no adapter).
- [x] **Step 4: Implement** the migrations, entities (`@OneToMany(mappedBy = "timeDeposit")` withdrawals, `BigDecimal` columns) and both repositories.
  - The adapter maps entity → domain with `new TimeDeposit(id, planType, balance.doubleValue(), days)`.
  - `saveBalances` looks each entity up by id and sets the balance with the Global Constraints money mapping.
- [x] **Step 5: Run `mvn verify`.** Expected: PASS, with all previous tests green.
- [x] **Step 6: Commit:** `feat: persist time deposits and withdrawals in PostgreSQL`.

### Task 4: OpenAPI contract, generated API and REST controller

**Files:**
- Create: `src/main/resources/static/openapi.yaml`
  - OpenAPI 3.0.3, tag `TimeDeposits`.
  - The two operations, `operationId`s and schemas exactly as in the spec's "API contract" section.
  - `balance` and `amount` are `type: number` with no `format`, so they generate as `BigDecimal`.
  - All properties are `required`.
- Modify: `pom.xml`: add `openapi-generator-maven-plugin` `7.27.0` with:
  - goal `generate`, `inputSpec` `${project.basedir}/src/main/resources/static/openapi.yaml`, generator `spring`;
  - `apiPackage` `org.ikigaidigital.rest.controller`, `modelPackage` `org.ikigaidigital.rest.dto`, `modelNameSuffix` `Dto`;
  - `interfaceOnly=true`, `useTags=true`, Jakarta and Boot-4-compatible options, and no generated documentation or annotation-library extras that need extra dependencies.
- Create: `src/main/java/org/ikigaidigital/rest/controller/TimeDepositController.java`
- Create: `src/main/java/org/ikigaidigital/rest/dto/TimeDepositDtoMapper.java`
- Test: `src/test/java/org/ikigaidigital/rest/dto/TimeDepositDtoMapperTest.java`
- Test: `src/test/java/org/ikigaidigital/rest/TimeDepositApiIT.java`

**Interfaces:**
- Consumes: `TimeDepositService.getAll()` and `updateAllBalances()` (Task 2), and the seed data (Task 3).
- Produces:
  - generated `TimeDepositsApi`, `TimeDepositDto` and `WithdrawalDto`;
  - `@RestController TimeDepositController implements TimeDepositsApi`:
    - `getAllTimeDeposits()` → `200` with the mapped list;
    - `updateAllBalances()` → `ResponseEntity.noContent()`.
  - `TimeDepositDtoMapper.toDto(TimeDepositDetails)`: a static or `@Component` method, the builder's choice.

- [x] **Step 1: Write `TimeDepositDtoMapperTest`.**
  - `mapsAllFiveFieldsAndWithdrawals`:
    - input: deposit `(7, "premium", 241.0, 60)` with withdrawal `(2, 100.00, 2026-02-01)`;
    - the DTO has id `7`, planType `"premium"`, balance `isEqualByComparingTo("241.00")` with scale `2`, days `60`, and one withdrawal with the same id, amount and date.
  - `balanceDoubleNoiseIsRoundedToCents`: balance `0.1 + 0.2` → `0.30`.
- [x] **Step 2: Write `TimeDepositApiIT extends PostgresIntegrationTest`**, using `RestClient` against `http://localhost:{port}`.
  - `getReturnsAllSeededDepositsWithWithdrawals`: deserialize to `TimeDepositDto[]` and expect the 6 deposits from the Task 3 table (ids, plans, balances, days, withdrawal counts 0/1/2/0/0/0).
  - `getSerializesWithdrawalDatesAsIsoDates`: the raw body contains `"date":"2026-01-15"`.
  - `postUpdatesAllBalancesAndReturnsNoContent`: `POST` returns status `204` with an empty body. A following `GET` shows the "after one update" column from the Task 3 table.
  - `emptyDatabaseReturnsEmptyListAndUpdateSucceeds`:
    - `JdbcTemplate` deletes all withdrawals and then all deposits;
    - `GET` returns `[]`;
    - `POST` returns `204`.
  - `unknownPlanTypeIsListedAndEarnsNoInterest`:
    - insert deposit id `100`, plan `"gold"`, balance `500.00`, days `100`;
    - after `POST`, `GET` shows it with balance `500.00`.
  - `applicationExposesExactlyTwoApiOperations`: `RequestMappingHandlerMapping.getHandlerMethods()` filtered to bean types in package `org.ikigaidigital` → exactly the two mappings `GET /time-deposits` and `POST /time-deposits/balance-updates`.
- [x] **Step 3: Run `mvn verify -Dit.test=TimeDepositApiIT -Dtest=TimeDepositDtoMapperTest`.** Expected: FAIL (generated types and controller missing).
- [x] **Step 4: Implement** `openapi.yaml`, the generator plugin config, `TimeDepositDtoMapper` (money mapping from Global Constraints; withdrawal amounts copied as stored) and `TimeDepositController`.
- [x] **Step 5: Run `mvn verify`.** Expected: PASS.
- [x] **Step 6: Commit:** `feat: expose time deposit REST API from OpenAPI contract`.

### Task 5: Swagger UI on the hand-written contract, and API docs

**Files:**
- Modify: `src/main/resources/application.yaml`: `springdoc.api-docs.enabled: false`, `springdoc.swagger-ui.url: /openapi.yaml`.
- Create: `docs/api.md`
- Modify: `CLAUDE.md`: Commands section, add `mvn spring-boot:run  # starts Postgres via compose.yaml, Swagger at http://localhost:8080/swagger-ui/index.html`.
- Test: `src/test/java/org/ikigaidigital/rest/SwaggerIT.java`

- [x] **Step 1: Write `SwaggerIT extends PostgresIntegrationTest`.**
  - `servesTheHandWrittenContract`: `GET /openapi.yaml` returns 200, and the body contains `operationId: getAllTimeDeposits` and `operationId: updateAllBalances`.
  - `swaggerUiIsAvailable`: `GET /swagger-ui/index.html` returns 200.
  - `springdocGeneratedDocsAreDisabled`: `GET /v3/api-docs` returns 404.
- [x] **Step 2: Run `mvn verify -Dit.test=SwaggerIT`.** Expected: FAIL on `springdocGeneratedDocsAreDisabled`.
- [x] **Step 3: Implement** the properties.
  - If springdoc 3.2.1 also disables Swagger UI when `api-docs` is off, record that as a ruling.
  - In that case, keep `api-docs` enabled, drop the 404 assertion, and keep the other two. The controller-derived document still has only the two operations, which `applicationExposesExactlyTwoApiOperations` guards.
- [x] **Step 4: Write `docs/api.md`:**
  - prerequisites: JDK 17+, Docker;
  - `mvn spring-boot:run`;
  - the Swagger UI URL;
  - how to call each operation from Swagger, with the expected seed results (Task 3 table);
  - equivalent `curl` commands;
  - a note that each `POST` applies one more month of interest.
- [x] **Step 5: Manual smoke test.** Run `mvn spring-boot:run`, then `curl -s localhost:8080/time-deposits` (6 deposits), then `curl -s -X POST -o /dev/null -w '%{http_code}' localhost:8080/time-deposits/balance-updates` (`204`). Stop the app.
- [x] **Step 6: Run `mvn verify`.** Expected: PASS. All spec-001 tests are unchanged (`git diff --stat` shows no changes to the three spec-001 test files).
- [x] **Step 7: Commit:** `docs: serve the OpenAPI contract in Swagger UI and document the API`.

### Task 6: Age deposits by one month per balance update (spec amendment "Deposit ageing")

**Files:**
- Modify: `src/main/java/org/ikigaidigital/domain/model/TimeDeposit.java`: add `public void setDays(int days)`. Nothing else changes.
- Modify: `src/main/java/org/ikigaidigital/repository/api/TimeDepositRepository.java`: rename `saveBalances` to `void saveAll(List<TimeDeposit> deposits)`, which persists `balance` and `days`.
- Modify: `src/main/java/org/ikigaidigital/repository/impl/JpaTimeDepositRepository.java` and `TimeDepositEntity.java` (`setDays`).
- Modify: `src/main/java/org/ikigaidigital/business/TimeDepositService.java`:
  - add `static final int DAYS_PER_MONTH = 30`, with a comment citing the standard 30/360 day-count convention, at the increment;
  - `updateAllBalances` becomes: lock and load → `calculator.updateBalance(list)` → `deposit.setDays(deposit.getDays() + DAYS_PER_MONTH)` for each deposit → `saveAll(list)`.
- Modify: `src/main/resources/static/openapi.yaml`: the `updateAllBalances` description says it then adds 30 days to every deposit.
- Modify: `docs/api.md`: add a "days after one update" column, a note that each update ages deposits by 30 days (30/360), and the two-update example.
- Modify tests:
  - `business/TimeDepositServiceTest.java` and `business/InMemoryTimeDepositRepository.java`
  - `repository/impl/JpaTimeDepositRepositoryIT.java`
  - `business/TimeDepositServiceConcurrencyIT.java`
  - `rest/TimeDepositApiIT.java`

**Expected values on the seed data.** Interest uses the `days` from before each update.

| id | after update 1 (balance / days) | after update 2 (balance / days) |
|---|---|---|
| 1 basic | 1201.00 / 75 | 1202.00 / 105 |
| 2 student | 401.00 / 130 | 402.00 / 160 |
| 3 premium | 241.00 / 90 | 242.00 / 120 |
| 4 basic | 1000.00 / 50 | 1000.83 / 80 (day 50 earns 1000/1200 = 0.8333 → 0.83) |
| 5 student | 400.00 / 430 | 400.00 / 460 |
| 6 premium | 240.00 / 70 | 241.00 / 100 (day 70 earns 1.00) |

- [x] **Step 1: Update the tests.**
  - `TimeDepositServiceTest`:
    - rename to the fake's `saveAll`;
    - new test `updateAllBalancesAgesEveryDepositByThirtyDaysAfterCalculatingInterest`: input premium 240.00 at day 40 → saved balance `240.00` (no interest at day 40), saved days `70`; basic 1200.00 at day 45 → `1201.00` / `75`.
  - `JpaTimeDepositRepositoryIT`:
    - `saveBalancesPersistsTwoDecimalValues` → `saveAllPersistsTwoDecimalBalancesAndDays`: also sets deposit 1's days to `75` and asserts the stored `days` is `75`;
    - the lock test's expected value is unchanged (`1201.00`).
  - `TimeDepositServiceConcurrencyIT`: expected balances become `1202.00, 402.00, 242.00, 1000.83, 400.00, 241.00` and days `105, 160, 120, 80, 460, 100`.
  - `TimeDepositApiIT`:
    - `postUpdatesAllBalancesAndReturnsNoContent` also asserts days `75, 130, 90, 50, 430, 70`;
    - new `secondUpdateUsesTheAgedDeposits`: two `POST`s, then balances and days equal the "after update 2" column.
- [x] **Step 2: Run `mvn verify`.** Expected: FAIL. The build fails to compile on `saveAll`/`setDays`, and once it compiles, the days assertions fail.
- [x] **Step 3: Implement** the production changes listed under Files.
- [x] **Step 4: Run `mvn verify`.** Expected: PASS. The spec-001 test files are unchanged, and the 146 characterization tests still pass.

### Task 7: Fix the dependency findings from the architecture check

**Files:**
- Create: `src/main/java/org/ikigaidigital/domain/service/DepositAgeing.java`:
  - `public static final int DAYS_PER_MONTH = 30`, with the 30/360 comment;
  - `public void advanceOneMonth(List<TimeDeposit> deposits)`.
- Create: `src/main/java/org/ikigaidigital/domain/model/Money.java`: `public static BigDecimal toCents(double amount)`, implemented as `BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP)`.
- Modify:
  - `application/DomainConfiguration.java`: add `@Bean DepositAgeing depositAgeing()`.
  - `business/TimeDepositService.java`: the constructor takes `(TimeDepositRepository, TimeDepositCalculator, DepositAgeing)`. Remove the constant and the inline increment; call `depositAgeing.advanceOneMonth(deposits)`.
  - `repository/impl/JpaTimeDepositRepository.java` and `rest/dto/TimeDepositDtoMapper.java`: use `Money.toCents` and delete the private `toMoney` copies.
- Test:
  - Create `domain/service/DepositAgeingTest.java` and `domain/model/MoneyTest.java`.
  - Modify `business/TimeDepositServiceTest.java` (constructor only) and `ArchitectureTest.java`.

- [x] **Step 1: Write the tests.**
  - `DepositAgeingTest.advancesEveryDepositByThirtyDaysAndLeavesBalancesUntouched`: deposits at days 0, 45, 365 become 30, 75, 395, with balances unchanged.
  - `MoneyTest`:
    - `0.1 + 0.2` → `0.30`;
    - `241.0` → `241.00` with `scale()` 2;
    - `Math.nextDown(1201.0)` → `1201.00`.
  - `ArchitectureTest`:
    - `domainDependsOnNothingOutsideItself`: no `org.ikigaidigital.business`, `.repository`, `.rest` or `.application` imports under `domain/`;
    - `repositoryApiDoesNotDependOnItsImplementation`;
    - `importsUnder` throws when the directory is missing.
- [x] **Step 2: Run `mvn test`.** Expected: FAIL (compilation: `DepositAgeing` and `Money` are missing).
- [x] **Step 3: Implement** the files above.
- [x] **Step 4: Run `mvn verify`.** Expected: PASS, with all existing unit and integration tests unchanged in behaviour.
