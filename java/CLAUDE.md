# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project
XA Bank time deposit kata (Java track; ignore sibling language dirs). Full brief: `../README.md`.
Goal: refactor the starter domain code into a service with a DB (`timeDeposits`, `withdrawals` tables) and **exactly two** REST endpoints: update all balances, and get all deposits (`id`, `planType`, `balance`, `days`, `withdrawals`).
Stack: Spring Boot, PostgreSQL, OpenAPI/Swagger contract, Hexagonal Architecture, Testcontainers (Postgres container for integration tests). Invalid input/exception handling is out of scope.

## Hard constraints
- No breaking changes to `TimeDeposit` (constructor, getters, `setBalance`).
- `TimeDepositCalculator.updateBalance(List<TimeDeposit>)` keeps its signature and exact behavior.
  Current rules: no interest if `days <= 30`; `basic` 1%/12; `student` 3%/12 while `days < 366`; `premium` 5%/12 when `days > 45`;
  unknown plans get 0; interest rounded HALF_UP to 2 decimals, then added to the `double` balance. Lock this with characterization tests before refactoring.

## Coding rules
- Hexagonal layout: domain has no framework dependencies; adapters (REST, persistence) depend on ports, never the reverse.
- Interest logic must be extensible (e.g. one strategy per plan type), following SOLID.
- TDD: write the failing test first. Unit tests for domain, Testcontainers for persistence/integration.

## Spec-driven development
Every feature follows: **spec → plan → implement → verify**.
1. Write a spec in `docs/specs/` (what and why, acceptance criteria). Get approval before planning.
2. Write a plan in `docs/plans/` (ordered, small tasks referencing the spec). Get approval before coding.
3. Implement task by task, ticking them off in the plan.
4. Verify against the spec's acceptance criteria (`mvn verify`) before calling it done.

## `docs/` folder
- `docs/specs/` – feature specs, named `NNN-short-name.md`.
- `docs/plans/` – implementation plans, same number as their spec.
- `docs/ai-assisted-development.md` – tools, setup, rules/prompts used, and which parts were AI-assisted and why (required by the brief).
- `docs/api.md` – how to trigger the endpoints via Swagger.

## Commands
```bash
mvn test                                         # all tests
mvn test -Dtest='TimeDepositCalculatorTest#updateBalance_Test'  # single test (class or #method)
mvn verify                                       # full build incl. integration tests
```
