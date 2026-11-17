# AGENTS.md

Ruslo is a Java library for long-running business processes on H2 and PostgreSQL. Steps and awaits form a state
machine. State lives in `xs_*` tables and a lease dispatcher runs ready processes across one or more nodes.
The project is before 1.0 and may break public API compatibility to simplify the library.

## Project Structure

Maven multi-module project with Java 21 and a Spring Boot 4.0 baseline.

- Start engine work in `ruslo-core`. For execution issues, check `core.runtime`, `core.runtime.worker`,
  `core.dispatcher` and the related starter properties.
- The public core API is `core.api.*` and `core.spi.*`. Examples and user-facing code in other modules must use
  those packages for core imports.
- Spring integration lives in `ruslo-spring-boot-starter` and test support in `ruslo-spring-boot-starter-test`.
- See [Admin UI instructions](ruslo-admin-ui/AGENTS.md) and [benchmark instructions](ruslo-performance-test/AGENTS.md)
  for module-specific rules. See the [examples README](ruslo-examples/README.md) for tutorials, recipes and the demo.

## Build and Test

- Run core tests with `./mvnw test -pl ruslo-core`. Select a test with `'-Dtest=ClassName#methodName'` and add
  `-am` when upstream modules need rebuilding.
- Use `BaseTest` for DB-backed engine tests and obtain databases and dialects from `TestDatabases`. H2 is the
  default. For PostgreSQL, run `./mvnw test -pl ruslo-core -Ppostgresql-tests` with Docker.
- Use `@ProcessEngineTest` for Spring integration tests and control time through the emulator. See the
  [testing guide](docs/en/testing.md) for clock reset options.
- The emulator joins an enclosing transaction, including a test's Spring `@Transactional` transaction. Run it
  outside an enclosing transaction to test retries and rollback, and use a running dispatcher to verify production
  transaction boundaries.

### Definition of Done

- A behavior change or bug fix includes a test that fails without it.
- For code changes, run `./mvnw spotless:apply` and test the affected modules. For cross-module code changes or
  SQL changes, run `./mvnw verify`.
- For user documentation changes, install dependencies from `docs/requirements.txt` as needed and run
  `mkdocs build --strict`.

## Design Principles

- Prefer direct implementations with fewer independent concepts, valid combinations and special cases. Add
  abstractions and execution rules only for current requirements.
- Validate input at external boundaries. Internal code relies on caller and type invariants. Check mutable
  concurrent state where needed, such as `row_version`.

### Public API Rejections

Throw caller errors, invariant violations and infrastructure failures inside the engine transaction so it rolls
back. For expected rejections that depend on current process state, return the rejection from the transactional
action and throw it after the engine leaves its transaction via `ProcessRuntimeService.doInTransactionOrReject`.
This lets a Spring caller catch the rejection without marking its transaction rollback-only. Use a diagnostic
exception for these outcomes, including unexpected state in operator and test APIs. See
[application transactions](docs/en/spring.md#application-transactions) for examples.

### Execution Invariants

- Handlers report outcomes and definitions declare targets. `ProcessStep` returns `void` and `BranchingStep<O>`
  returns an outcome.
- Each worker transition commits in its own transaction. A transition to a state on the same worker continues under
  the same lease. Await handlers always run on the transactional `DEFAULT` worker.
- A transition to the current state preserves the message queue, `every` and `after` timers, and history, and clears
  errors and retry attempts. A step runs again and an await keeps waiting. All transitions go through
  `ProcessInstance.changeState`.

## Working Rules

- Keep implementation plans in the ignored `plans/` directory. Record lasting decisions in code, Javadoc, docs or
  the commit message.
- Update affected callers and Javadoc alongside API, configuration, schema, test utility or behavior changes.
- Update user docs, examples and `skills/ruslo/SKILL.md` where the public contract or recommended usage changes.
  Document internal safeguards in code and tests.
- For changes to Spring Boot properties, also update
  `ruslo-spring-boot-starter/src/main/resources/META-INF/additional-spring-configuration-metadata.json`.
- Migrations are append-only. Add the next `V<version>__<name>.sql` to both `h2/` and `postgresql/` under
  `ruslo-core/src/main/resources/db/migration/` and verify affected DAO queries. All engine tables use `xs_`.

### Examples

- Keep example `define()` methods readable as state graphs. Use method references for steps and handlers.
- Use fully typed handlers in production code, tests and examples. Raw types and casts on handlers are forbidden.
- Show one pattern per cookbook recipe. Steps outside that pattern only log. Add database work or service stubs
  only if the pattern requires them. Recipe tests drive outcomes and errors through input data, use no mocks, and
  assert process state and variables.

## Code Style

- Write code, Javadoc, comments, log and exception messages in English.
- Document public methods with business logic. Start Javadoc with a short third-person summary and cover the
  observable result, side effects, transactions and threading as relevant.
- Document shared rules once and link to them with `{@link}`. Use `@param` and `@return` for details beyond the
  signature and `@throws` for exceptions callers may need to handle. For internal classes, explain the reason
  for the design.

## Documentation

- Edit English first and keep `README.ru.md` and `docs/ru/` aligned with `README.md` and `docs/en/` in the same change.
- Keep docs focused on the model and recommended usage. Put exact method edge cases in Javadoc. Give each topic
  one home page. Keep the README and quickstart focused on the main path and link to those pages for details.
- In `docs/<lang>/`, process start, state, transitions, `ProcessContext` and search go in `processes.md`. Steps go
  in `steps.md`. Messages, events,
  timers and `sendMessageSync` go in `awaits.md`. Retries and `FAILED` go in `error-handling.md`. Worker modes go
  in `workers.md`, and pickup, leases, clocks and node failure in `dispatcher.md`.
- Spring integration and application transactions go in `spring.md`. Settings go in `configuration.md`, test
  tools in `testing.md`, maintenance in `operations.md`, UI actions in `admin-ui.md` and metrics in `metrics.md`.
- Add new pages under the same filename in both languages. Update `nav` and `nav_translations` in `mkdocs.yml`.
  Keep shared images in `docs/images/`. Use relative links within the site and absolute GitHub links to files
  outside `docs/`.
