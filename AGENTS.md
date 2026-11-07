# AGENTS.md

ruslo is a Java library for long-running business processes stored in a relational database. A process is a state
machine: steps run code and move it to the next state, awaits wait for messages and timers. State lives in `xs_*`
tables, a lease dispatcher runs ready processes on one or many nodes. Supported databases: H2 and PostgreSQL.

The project is before 1.0: public API may change incompatibly when it makes the library simpler. Update callers, docs,
examples and `skills/ruslo/SKILL.md` in the same change.

## Project Structure

Maven multi-module project (`Java 21`, Spring Boot 4.0 baseline):

- **`ruslo-core`**: engine, runtime, lease dispatcher, DAO layer, metrics and public API. Primary business logic
  lives here; start here unless the task is clearly about Spring, admin UI, examples or test support.
- **`ruslo-spring-boot-starter`**: auto-configuration, property binding, JDBC and Micrometer integration.
- **`ruslo-spring-boot-starter-test`**: test support for Spring Boot integration tests.
- **`ruslo-admin-ui`**: Thymeleaf + Pico CSS admin UI and REST endpoints for process management.
- **`ruslo-examples`**: `quickstart` (tutorial code), `cookbook` (recipes, one package per recipe; also the runnable
  demo), `standalone-core` (no Spring).
- **`ruslo-performance-test`**: performance and load scenarios.
- **`skills/ruslo/SKILL.md`**: agent skill for library users.
- **`docs/`**: user documentation, published as a site.

### Packages in `ruslo-core`

Public API is `core.api.*` and `core.spi.*`; everything else is internal. Examples and user-facing code of other
modules must not import internal packages.

| Package | Responsibility |
|---|---|
| `core.api` | Engine entry points and setup: `ProcessEngine`, `ProcessEngineBuilder`, `ProcessEngineAdmin`, `ProcessEngineEmulator` |
| `core.api.definition` | Process description: definitions, states, handlers, error policy, `ProcessContext` |
| `core.api.view` | Reading process state and search queries |
| `core.api.exception` | Public exceptions |
| `core.api.test` | Test support: `MutableClock`, `TestProcessContext` |
| `core.spi` | Extension contracts: `Database`, `RowMapper`, `ProcessEngineMetrics`; `PlainJdbcDatabase` |
| `core.database` | SQL dialect detection |
| `core.definition` | Definition registration and validation |
| `core.runtime` | `ProcessRuntimeService`: start, message delivery, resume |
| `core.runtime.process` | Process instance model and DAO |
| `core.runtime.worker` | Step execution, workers, runtime error handling |
| `core.dispatcher` | Polling scheduler and dispatcher abstraction |
| `core.dispatcher.lease` | Lease claim, fencing and multi-node dispatching; invariants in the `LeaseDispatcher` Javadoc |
| `core.search` | Process search |
| `core.management` | Schema migration and management operations |
| `core.metrics` | Metrics abstraction, in-memory and no-op implementations |

For process execution issues, inspect the runtime path (`core.runtime`, `core.runtime.worker`, `core.dispatcher`) and
the related Spring properties in the starter.

## Build & Test

```bash
./mvnw clean install                                    # Build and install all modules
./mvnw test -pl ruslo-core                              # Tests of one module (add -am if upstream modules changed)
./mvnw test -Dtest=TestClassName#methodName             # One test class or method
./mvnw test -pl ruslo-core -Ppostgresql-tests           # Core tests against PostgreSQL (Testcontainers, needs Docker)
./mvnw test -Pperf-tests                                # Performance tests only (excluded by default)
./mvnw verify                                           # Full build with tests and checks, as in CI
./mvnw spotless:apply                                   # Format code
./mvnw spring-boot:run -pl ruslo-examples/cookbook -am  # Demo app: http://localhost:8080, admin UI at /ruslo
```

- Core tests extend `BaseTest` and run on H2 by default. `postgresql-tests` starts one PostgreSQL container per JVM
  and gives every test its own schema. Get databases and the dialect from `TestDatabases`, never hardcode H2.
- Spring integration tests use `@ProcessEngineTest` from `ruslo-spring-boot-starter-test`: the dispatcher is
  disabled and steps run through `ProcessEngineEmulator`. Control time only through the emulator.

### Definition of done

- A behavior change or bug fix comes with a test that fails without it.
- `./mvnw spotless:apply` is run; formatting is owned by Spotless (Eclipse formatter `eclipse-formatter.xml` and
  import order, configured in the root `pom.xml`); `verify` and CI fail on unformatted code.
- Tests of the affected modules pass; for changes across modules or in SQL, run `./mvnw verify`.
- After a docs change, `mkdocs build --strict` passes (see [Documentation site](#documentation-site)).
- Docs, examples, `SKILL.md` and configuration metadata are updated as required by [Working Rules](#working-rules).

## Design Principles

- Simplicity is the main value of this library, in the public API and in the implementation. Over-engineering is a defect, not a reserve for the future.
- When choosing between solutions, prefer the one with fewer independent concepts, allowed combinations and special rules. The number of types or lines is not the goal: one class with nullable fields and many valid combinations is more complex than three small records.
- Simplicity of execution rules matters most: how the engine selects events, commits transitions and handles errors. Every new rule or exception to a rule must be justified by a real requirement.
- Always choose the simplest, most direct, and most maintainable solution that works. Do not add extra abstractions, wrapper classes, design patterns, or future-proofing unless explicitly asked.

### Exceptions vs. return values in the public API

Engine operations join the caller's transaction, and in Spring any exception escaping them marks that transaction rollback-only. So:

- **An exception means an error**: a bug in the caller or definition, a broken invariant or an infrastructure failure (unknown process, undeclared message type, DB error, user handler failure). Rolling back the caller's transaction is the correct reaction.
- **An expected outcome is an exception thrown after the engine's transaction**: anything that depends on process state the caller does not control and that may change concurrently (the process left the await, completed, or was stopped in `FAILED` by the dispatcher). The transactional action returns the rejection and the engine throws it only after leaving its transaction (`ProcessRuntimeService.doInTransactionOrReject`), so a caught rejection does not mark the caller's transaction rollback-only, while an uncaught one rolls it back like any exception. Examples: `sendMessage` and `sendMessageSync` throw `MessageNotExpectedException` (also for a `FAILED` process in `sendMessageSync`), `resumeFailedProcess` throws `ProcessStatusException`. Never throw such an outcome from inside the engine's transaction, and never report it as a `boolean`: it is easy to ignore and carries no diagnostics.
- **Operator and test APIs may throw on unexpected state** (`ProcessEngineAdmin`, `ProcessEngineEmulator`): they run outside business transactions and their caller needs a diagnostic, not a flag.

### Execution model invariants

- Handlers report a result, the definition declares the target (`ProcessStep` returns nothing, `BranchingStep<O>`
  returns an outcome).
- Every transition is committed in its own transaction; if the next state uses the same worker, execution continues
  under the same lease. Await handlers always run on the transactional `DEFAULT` worker.
- A transition to the same state is not a state change: the queue, `every`, `after` and history are kept, the error
  is cleared; a step runs again, an await keeps waiting. The rule lives in `ProcessInstance.enterState`, the single
  path for every transition.
- Lease and fencing rules: the `LeaseDispatcher` Javadoc. User-facing rules: the docs pages in the
  [table below](#documentation).

## Working Rules

- Implementation plans go to `plans/` at the repository root; the folder is not committed. Everything worth keeping from a plan moves into code, Javadoc, docs and the commit message.
- If you change public API, configuration properties, database schema, or test utilities, also update the relevant docs/examples in the same change.
- If such a change affects what a library user writes (definitions, handlers, engine calls, error handling, workers, testing), also update the user-facing agent skill `skills/ruslo/SKILL.md` in the same change.
- If you add, rename, remove, or change defaults/semantics of Spring Boot configuration properties, update `ruslo-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json` and relevant docs/examples in the same change.
- Database migrations are append-only: never edit or delete an existing `V<n>__*.sql`. Add the next version for every
  supported database (`ruslo-core/src/main/resources/db/migration/h2/` and `.../postgresql/`, auto-discovered by
  `V<version>__<name>.sql`) and verify the affected DAO/query code. All engine tables use the `xs_` prefix.

### Examples

- `define()` reads as a plain state graph: no inline lambdas in it. A small step or handler becomes a method of the
  definition, referenced as `Definition::onPayment` (static) or `this::reserveSeat` (uses an injected bean).
- Raw types and casts on handlers are forbidden in production code, tests and examples.
- A cookbook recipe shows one engine pattern. Steps that are not the point of the recipe only log; no application DB
  work or service stubs unless the pattern needs them. Recipe tests use no Mockito and do not emulate failures with
  mocks: outcomes and errors come from input data, and tests check process state and variables.

### Admin UI

- Actions do not appear or disappear with process state: every action is always shown and disabled with a short
  reason when it does not apply (`disabled` instead of `th:if`).
- Each action lives in one place (e.g. Send message only in the Actions menu, not also as a toolbar button).

## Code Style

- **Language**: code, Javadoc, comments, log and exception messages are in English.
- **Public methods with business logic** must have a Javadoc comment. Simple getters and setters do not require Javadoc.

### Javadoc

Javadoc is a technical contract, not prose. Write the minimum the caller needs, by default one sentence.

- The first sentence says what the element does, in third person: `Starts…`, `Returns…`.
- Describe what the caller observes: result, exceptions, side effects, transaction and threading behavior. Do not retell the implementation.
- Describe a rule shared by several methods once (class, exception or `package-info` Javadoc) and link to it with `{@link}`.
- Write `@param` and `@return` only when they add something to the name and type. Keep `@throws` for every exception the caller may need to handle.
- Do not repeat in the text what a `@throws` or `@param` tag already says.
- Cut a sentence that restates a type or name, repeats a rule documented elsewhere, or explains internals.
- Internal classes: explain why, not what the code does. A short class Javadoc is usually enough.

### Documentation

- English is the primary version: `README.md`, `docs/en/*.md`. The Russian translation lives next to it: `README.ru.md`, `docs/ru/*.md`.
- Change the English version first, then update the Russian one in the same change so they say the same thing.
- Documentation may explain in more detail than Javadoc, but stays technical: no filler, no marketing.
- Documentation describes the model and the recommended usage. Exact edge cases of a method (argument checks,
  truncation, behavior in rare states) belong in its Javadoc, not in `docs/`.
- Every fact has one home page; other pages link to it instead of restating it. A behavior change should touch its
  home page, the Javadoc and `skills/ruslo/SKILL.md`, not every page that mentions the feature.

| Topic | Home in `docs/<lang>/` |
|---|---|
| Process description, transitions, start, `ProcessContext`, status, process search, definition evolution | `processes.md` |
| Linear and branching steps, step worker, repeated execution | `steps.md` |
| Await events, messages and their acceptance, `every`/`after`/`at` timers, event order, `sendMessageSync` | `awaits.md` |
| Workers, execution modes, transactions and at-least-once, capacity | `workers.md` |
| Error policy, give-up state, retries in an await, `FAILED` | `error-handling.md` |
| `next_run_at`, pickup, timer accuracy, multiple nodes, node failure, clocks | `dispatcher.md` |
| Starter, `SpringProcessDefinition`, application transactions in Spring | `spring.md` |
| Properties and builder methods | `configuration.md` |
| Emulator and `TestProcessContext` | `testing.md` |
| Production checklist, editing a process in SQL, retention | `operations.md` |
| Admin UI pages and actions | `admin-ui.md` |
| Metrics | `metrics.md` |

- `README.md` and `docs/en/quickstart.md` show only the main path and link to the home pages; Admin UI pages are
  described by what the user can do, not by how the page works.

#### Documentation site

`docs/` is published to GitHub Pages (`https://neherim.github.io/ruslo/`) with MkDocs Material by
`.github/workflows/docs.yml`; the configuration is `mkdocs.yml`.

- `docs/en/` and `docs/ru/` hold the same file names; the site language switcher maps pages by name.
  `docs/<lang>/index.md` is the site home page. Images shared by both languages are in `docs/images/`.
- A new page goes to both language folders and to `nav` in `mkdocs.yml`; translate its section names in
  `nav_translations`.
- Links between pages are relative (`steps.md#anchor`). Links to the rest of the repository (modules, examples)
  are absolute GitHub URLs: the site contains only `docs/`.
- The build is strict: a broken link or anchor fails CI. Check locally:
  `pip install -r docs/requirements.txt && mkdocs build --strict`, preview with `mkdocs serve` (http://localhost:8000).
