# AGENTS.md

## Code Style

- **Language**: code, Javadoc, comments, log and exception messages are in English.
- **Public methods with business logic** must have a Javadoc comment. Simple getters and setters do not require Javadoc.
- **Formatting** is owned by Spotless: the Eclipse formatter with `eclipse-formatter.xml` and import order, configured in the root `pom.xml`. Run `./mvnw spotless:apply` before every commit; `./mvnw verify` and CI fail on unformatted code.

### Javadoc

Javadoc is a technical contract, not prose. Write the minimum the caller needs.

- The first sentence says what the element does, in third person: `Starts…`, `Returns…`.
- Describe what the caller observes: result, exceptions, side effects, transaction and threading behavior. Do not retell the implementation.
- Describe a rule shared by several methods once (class, exception or `package-info` Javadoc) and link to it with `{@link}`.
- Write `@param` and `@return` only when they add something to the name and type. Keep `@throws` for every exception the caller may need to handle.
- Do not repeat in the text what a `@throws` or `@param` tag already says.
- Internal classes: explain why, not what the code does. A short class Javadoc is usually enough.

### Documentation

- English is the primary version: `README.md`, `docs/*.md`. The Russian translation lives next to it: `README.ru.md`, `docs/ru/*.md`.
- Change the English version first, then update the Russian one in the same change so they say the same thing.
- Documentation may explain in more detail than Javadoc, but stays technical: no filler, no marketing.
- Documentation describes the model and the recommended usage. Exact edge cases of a method (argument checks,
  truncation, behavior in rare states) belong in its Javadoc, not in `docs/`.
- Every fact has one home page; other pages link to it instead of restating it. A behavior change should touch its
  home page, the Javadoc and `skills/ruslo/SKILL.md`, not every page that mentions the feature.

| Topic | Home |
|---|---|
| States, handlers, awaits, timers, `ProcessContext`, definition evolution | `docs/concepts.md` |
| Transactions, at-least-once, worker modes, message acceptance, timer accuracy | `docs/guarantees.md` |
| Event order in an await, retry pause, Spring transactions, `sendMessageSync`, concurrency | `docs/execution.md` |
| Error policy, give-up state, `FAILED` | `docs/error-handling.md` |
| Properties and builder methods | `docs/configuration.md` |
| Emulator and `TestProcessContext` | `docs/testing.md` |
| Search, manual intervention, retention | `docs/operations.md` |

- `README.md` and `docs/quickstart.md` show only the main path and link to the home pages; Admin UI pages are
  described by what the user can do, not by how the page works.

## Design Principles

- Simplicity is the main value of this library, in the public API and in the implementation. Over-engineering is a defect, not a reserve for the future.
- When choosing between solutions, prefer the one with fewer independent concepts, allowed combinations and special rules. The number of types or lines is not the goal: one class with nullable fields and many valid combinations is more complex than three small records.
- Simplicity of execution rules matters most: how the engine selects events, commits transitions and handles errors. Every new rule or exception to a rule must be justified by a real requirement.

### Exceptions vs. return values in the public API

Engine operations join the caller's transaction, and in Spring any exception escaping them marks that transaction rollback-only. So:

- **An exception means an error**: a bug in the caller or definition, a broken invariant or an infrastructure failure (unknown process, undeclared message type, DB error, user handler failure). Rolling back the caller's transaction is the correct reaction.
- **An expected outcome is an exception thrown after the engine's transaction**: anything that depends on process state the caller does not control and that may change concurrently (the process left the await, completed, or was stopped in `FAILED` by the dispatcher). The transactional action returns the rejection and the engine throws it only after leaving its transaction (`ProcessRuntimeService.doInTransactionOrReject`), so a caught rejection does not mark the caller's transaction rollback-only, while an uncaught one rolls it back like any exception. Examples: `sendMessage` and `sendMessageSync` throw `MessageNotExpectedException` (also for a `FAILED` process in `sendMessageSync`), `resumeFailedProcess` throws `ProcessStatusException`. Never throw such an outcome from inside the engine's transaction, and never report it as a `boolean`: it is easy to ignore and carries no diagnostics.
- **Operator and test APIs may throw on unexpected state** (`ProcessEngineAdmin`, `ProcessEngineEmulator`): they run outside business transactions and their caller needs a diagnostic, not a flag.

## Working Rules

- Primary business logic lives in `ruslo-core`. Start there unless the task is clearly about Spring integration, admin UI, examples, or test support.
- Do not edit `target/` directories or generated files.
- Implementation plans go to `plans/` at the repository root; the folder is not committed. Everything worth keeping from a plan moves into code, Javadoc, docs and the commit message.
- If you change public API, configuration properties, database schema, or test utilities, also update the relevant docs/examples in the same change.
- If such a change affects what a library user writes (definitions, handlers, engine calls, error handling, workers, testing), also update the user-facing agent skill `skills/ruslo/SKILL.md` in the same change.
- If you add, rename, remove, or change defaults/semantics of Spring Boot configuration properties, update `ruslo-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json` and relevant docs/examples in the same change.
- When changing built-in database migrations, update SQL files for every supported database and verify affected DAO/query code.
- For process execution issues, inspect both the runtime path (`core.runtime`, `core.runtime.worker`, `core.dispatcher`) and the corresponding Spring properties in the starter module.

## Build & Test Commands

```bash
./mvnw clean compile                                    # Compile all modules
./mvnw clean install                                    # Build and install all modules
./mvnw test                                             # Run all tests
./mvnw test -pl ruslo-core                              # Run tests for a specific module
./mvnw test -Dtest=TestClassName                        # Run specific test class
./mvnw test -Dtest=TestClassName#methodName             # Run specific test method
./mvnw spring-boot:run -pl ruslo-examples/cookbook -am  # Start demo app (http://localhost:8080, admin at /ruslo)
./mvnw verify                                           # Full build with tests and checks
./mvnw spotless:apply                                   # Format code (run before every commit)
./mvnw spotless:check                                   # Check formatting without changing files
./mvnw test -Pperf-tests                                # Run performance tests only
./mvnw test -pl ruslo-core -Ppostgresql-tests  # Run core tests against PostgreSQL (Testcontainers, needs Docker)
```

Notes:

- Use `-am` when a module test depends on upstream modules being built.
- Performance tests are excluded by default and are enabled through the `perf-tests` profile.
- Core tests run on H2 by default. `postgresql-tests` sets `test.db=postgresql`: `TestDatabases` then starts one PostgreSQL container per JVM and gives every test its own schema. Get databases and the dialect from `TestDatabases` instead of hardcoding H2.
- The demo app starts on `http://localhost:8080`, admin UI is available at `/ruslo`.

## Project Structure

Maven multi-module project (`Java 21`, Spring Boot 4.0 baseline):

- **`ruslo-core`**: core engine, runtime, lease dispatcher, DAO layer, metrics, and public API.
- **`ruslo-spring-boot-starter`**: Spring Boot auto-configuration, property binding, JDBC integration, Micrometer integration.
- **`ruslo-spring-boot-starter-test`**: test support for Spring Boot integration tests.
- **`ruslo-admin-ui`**: Thymeleaf + Pico CSS admin UI and REST endpoints for process management.
- **`ruslo-examples`**: parent module for examples: `quickstart` (tutorial code), `cookbook` (recipes, one package per recipe; also the runnable demo), `standalone-core` (no Spring).
- **`ruslo-performance-test`**: performance and load-oriented test scenarios.

## Core Architecture

### Key Packages in `ruslo-core`

| Package | Responsibility |
|---|---|
| `core.api` | Engine entry points and setup: `ProcessEngine`, `ProcessEngineBuilder`, `ProcessEngineAdmin`, `ProcessEngineEmulator`, `ProcessStartBuilder`, `WorkerSpec` |
| `core.api.definition` | Process description: `ProcessDefinition`, `ProcessSpecBuilder`, `ProcessStep`, `BranchingStep<O>`, `ProcessMessageHandler<T>`, `BranchingMessageHandler<T, O>`, `ProcessTimerHandler`, `BranchingTimerHandler<O>`, `Interval`, `ErrorResult`, `ProcessErrorHandler`, `ProcessError`, `ProcessContext`, `ProcessType`, `ProcessState`, `MessageType`, `VariableType`, `WorkerId` |
| `core.api.view` | Reading process state: `ProcessQuery`, `ProcessInstanceDetails`, `ProcessVariablesView`, `ProcessInstanceSummary`, `ProcessSpecInfo`, `ProcessStatus` |
| `core.api.exception` | Public exceptions, e.g. `MessageNotExpectedException`, `ProcessStatusException`, `TooManyRowsException`, `DatabaseMigrationException` |
| `core.api.test` | Test support: `MutableClock`, `TestProcessContext` |
| `core.spi` | Public extension contracts: `Database`, `RowMapper`, `ProcessEngineMetrics`; `PlainJdbcDatabase` |
| `core.database` | SQL dialect detection from JDBC metadata |
| `core.definition` | Process definition registration and validation |
| `core.runtime` | `ProcessRuntimeService` and runtime orchestration |
| `core.runtime.process` | Process instance model, DAO, factory, process context |
| `core.runtime.worker` | Step execution, worker registry/factory, runtime error handling |
| `core.runtime.worker.impl` | Transactional and non-transactional worker implementations |
| `core.dispatcher` | Polling scheduler and dispatcher abstraction |
| `core.dispatcher.lease` | Lease claim, fencing and capacity-aware multi-node dispatching |
| `core.search` | Process search implementation, service, and DAO |
| `core.management` | Schema management and engine management operations |
| `core.metrics` | Engine metrics abstraction and in-memory / no-op implementations |

Public API is `core.api.*` and `core.spi.*`; everything else is internal. Examples and other modules' user-facing code must not import internal packages.

### Engine Lifecycle

1. Build the engine via `ProcessEngineBuilder`.
2. Call `.build()` to create a `ProcessEngine`.
3. Call `.start()` to optionally run DB migration and start the dispatcher scheduler.
4. `ProcessDispatchScheduler` polls for runnable processes and delegates to `ProcessDispatcher`.
5. Dispatcher selects ready processes and submits them to workers.
6. `ProcessRuntimeService` handles process start, message delivery, and resume.

### Execution Modes

- **Transactional worker**: step logic and engine persistence run in one transaction; this is the default mode.
- **Non-transactional worker**: step logic runs outside the engine transaction; useful when at-least-once semantics are acceptable.

Every transition is committed in its own transaction; if the next state uses the same worker, execution continues under the same lease. Await handlers always run on the transactional `DEFAULT` worker.

Spring property values map to these modes as:

- `transactional`
- `non_transactional`

### Horizontal Scaling (Leases)

- Ready processes are claimed individually with `FOR UPDATE SKIP LOCKED`.
- Each claim stores the UUID of the current instance registration in `lease_owner` (new on every start and after a lost registration is re-registered); business-state fencing uses `lease_owner + row_version`.
- Claim and release do not change `row_version`; release checks the current owner.
- Transactional workers retain a row lock while executing; non-transactional workers validate owner and row version.
- Instance liveness (`xs_application_instance.expires_at`) uses database time (`current_timestamp`); process readiness (`next_run_at`) uses the engine clock.
- `lease_owner` has no foreign key. Recovery deletes expired registrations and releases every lease whose owner is not registered, skipping locked rows (`FOR UPDATE SKIP LOCKED`); skipped rows are released on the next tick.

### Database Schema

- All engine tables use the `xs_` prefix.
- Built-in schema migrator: `ruslo-core/src/main/java/io/github/neherim/ruslo/core/management/DatabaseSchemaMigrator.java`.
- SQL migrations: `ruslo-core/src/main/resources/db/migration/`.
- To add a migration, create matching files for every supported database, e.g.
  `db/migration/h2/V2__some_change.sql` and `db/migration/postgresql/V2__some_change.sql`.
  Migration files are auto-discovered by `V<version>__<name>.sql`.
- Key tables: `xs_process_instance`, `xs_process_state_hist`.

### Process Definitions

- Handlers report a result, the definition declares the target (`ProcessStep` returns nothing, `BranchingStep<O>` returns an outcome).
- A transition to the same state is not a state change: the queue, `every`, `after` and history are kept, the error is cleared; a step runs again, an await keeps waiting. The rule lives in `ProcessInstance.enterState`, the single path for every transition.
- Raw types and casts on handlers are forbidden in production code, tests and examples.
- User-facing rules and compiler-error hints: `docs/concepts.md`.

## Testing

Use `ruslo-spring-boot-starter-test` for Spring Boot integration tests.

Key testing pieces:

- `@ProcessEngineTest` imports the `ProcessEngineTestConfiguration` auto-configuration (via `@ImportAutoConfiguration`).
- It disables the dispatcher via `ruslo.dispatcher.enabled=false`.
- It registers `ProcessEngineEmulator` for deterministic step execution in tests (`runOneStep`, `runUntilStop`; created by `new ProcessEngineEmulator(processEngine)`, uses the engine's clock); `emulator.fastForwardTime` / `setTime` advance timers (engine clock is a `MutableClock` bean `processEngineClock`, not a default candidate; tests control time only through the emulator). `ProcessEngineTestExecutionListener` (registered in `spring.factories`) calls `emulator.resetTime()` before each test method.
