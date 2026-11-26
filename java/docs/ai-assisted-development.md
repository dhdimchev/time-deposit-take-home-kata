# AI-assisted development

This document covers requirement 6 of the brief: the AI tools and setup used, the rules and configuration given to the
agent, and which parts of the solution were AI-assisted and why.

## Tools

| Tool | Role |
|---|---|
| **Claude Code** (CLI, model Claude Opus 5.5) | The coding agent. It reads the repo, runs Maven and Docker, writes code, tests and docs, and dispatches reviewer subagents. |
| **superpowers** plugin | A library of workflow skills the agent follows: `brainstorming`, `writing-plans`, `executing-plans`, `test-driven-development`, `systematic-debugging`, `requesting-code-review`, `verification-before-completion`. |
| **jdtls-lsp** plugin | Connects Claude Code to the Eclipse JDT language server for Java code intelligence. |
| **IntelliJ IDEA** | The IDE, for reading and editing code by hand and for running and debugging the app. Claude Code runs beside it and sees the file or selection that is open. |
| **Subagents** (Claude Opus) | Fresh-context reviewers. They review each design before planning and review finished work before it is accepted. |

## Setup (reproducible)

Everything an agent needs is committed in the repository:

- **`CLAUDE.md`** is loaded into every Claude Code session. It contains:
  - the project context and the brief's hard constraints (no breaking changes to `TimeDeposit`, `updateBalance`
    keeps its signature and behaviour);
  - the package structure and its dependency rules;
  - the coding rules (hexagonal architecture, SOLID, TDD);
  - the spec-driven workflow;
  - the build commands.
- **`.claude/settings.json`** (project scope) enables the `superpowers` and `jdtls-lsp` plugins from the official
  marketplace, and pre-approves only `mvn test` and `mvn verify`. Personal overrides go in
  `.claude/settings.local.json`, which git ignores.

To get the same setup on a new machine:
1. Install Claude Code, a JDK 17+, Maven and Docker.
2. Install the Java language server, which the plugin does not bundle (`yay -S jdtls` on Arch, `brew install jdtls`
   on macOS).
3. Start `claude` in the `java/` directory and trust the folder. Claude Code then offers to install both plugins.

## Rules and workflow given to the agent

The working rules come from `CLAUDE.md`:

1. **Spec → plan → implement → verify.**
   - Every feature starts with a spec in `docs/specs/`, which the developer approves.
   - Then comes a plan in `docs/plans/`, also approved.
   - The agent implements the plan task by task, ticking off steps.
   - It verifies against the spec's acceptance criteria with `mvn verify`.
2. **Test first.**
   - Characterization tests locked the calculator's behaviour before any refactoring.
   - Each new behaviour started with a failing test.
   - Bugs found in review were reproduced before they were fixed.
3. **Hexagonal dependency rules.** These are enforced by `ArchitectureTest` rather than left to convention.
4. **The developer stays in charge of decisions and history.**
   - The agent asks when a choice changes what gets built.
   - It records the decisions it makes on its own as rulings, with their cost if wrong.
   - It does not commit unless asked.
5. **Independent review.** Finished work goes to a fresh reviewer subagent. Its findings are re-graded, and only real
   issues are fixed, each with a test.

## Which parts were AI-assisted, and why

| Area | Who did what | Why AI |
|---|---|---|
| **Characterization tests** (`TimeDepositCalculatorTest`, `TimeDepositCalculatorNumericalCharacterizationTest`) | The developer defined the scenario catalogue and the rules: no production changes, expected values derived independently, nothing that hides precision errors. The agent analysed the IEEE-754 behaviour and wrote the tests, reaching 100% line and branch coverage. A reviewer agent then found gaps (HALF_UP was never actually pinned, and some rate-expression rewrites went undetected), which the agent closed. | Floating-point and rounding edge cases are tedious and error-prone by hand. The agent could probe exact binary values and run mutation-style checks quickly. |
| **Calculator refactor** (`TimeDepositCalculator`, `PlanType`) | The developer led the refactor: the `PlanType` enum with values and rates, the naming, and the move to sequential guard clauses. The agent reviewed each iteration against the characterization suite and implemented the final version to the developer's direction. | Pair-programming against a safety net: the tests, not the agent, decided what was correct. |
| **REST API and persistence** (spec and plan 002) | The developer decided the architecture: contract-first OpenAPI, PostgreSQL, the hexagonal package structure and its dependency rules, the row lock for concurrent updates, and ageing deposits by 30 days per update (30/360). The agent wrote the spec, plan, code, migrations, tests and `docs/api.md` to those decisions. | Large, well-specified scaffolding: Spring Boot 4, Flyway, Testcontainers and the OpenAPI generator. With the decisions made up front, implementation was mostly mechanical. |
| **Scalability and extensibility** (spec and plan 003) | The developer set the direction: one strategy per plan type; batched updates that are idempotent and resumable after a mid-run failure; no pagination on `GET`. After review, the developer also rejected a streaming `GET` as over-engineering for this service. The agent designed and implemented it. A reviewer agent found a race in which a stale worker could pay a month twice; it was reproduced with a failing test and fixed. | Concurrency and failure handling across batches, run tracking and `SKIP LOCKED` is where an independent reviewer and test-first fixes pay off most. |
| **Diagnostics during manual testing** | While the developer was exercising the API from an IntelliJ debug session, the agent traced a stalled update through `pg_stat_activity` and a thread dump. It was a paused debugger thread holding the batch's row lock: the lock was doing its job, and no code change was needed. | Fast evidence gathering across the app, the database and the JVM. |
| **Architecture check and fixes** | The developer requested an audit of the hexagonal boundaries. The agent checked the imports against the dependency rules and proposed fixes: move month ageing into the domain, merge the money rounding into one helper, and add the missing `ArchitectureTest` rules. The developer approved each fix and kept the trade-offs deliberately (e.g. no inbound-port interfaces for a single adapter). | A mechanical audit, with the developer deciding which trade-offs to keep. |

What stayed with the developer:
- every product and architecture decision;
- reviewing and approving each spec and plan;
- manual testing through Swagger and IntelliJ;
- the final say on naming and code style.

## Lessons
- **Lock behaviour before refactoring.** The characterization suite caught every regression in the manual
  refactor.
- **Independent review catches what the author misses.** It found the unpinned HALF_UP mode, a host-port clash in
  `compose.yaml`, a gap in the architecture test, and a stale-worker race in the resumable update.
- **Write decisions down.** Specs and plans in the repo make the AI's work reviewable and reproducible.
- **Prefer the simplest design that meets the need.** A streaming `GET` was built, reviewed and then deliberately
  reverted: the conventional, contract-implementing endpoint with a single query was the better trade-off.
