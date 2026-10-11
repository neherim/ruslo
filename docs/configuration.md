# Configuration

English | [Русский](ru/configuration.md)

The properties on this page belong to the Spring Boot auto-configuration (`ruslo-spring-boot-starter`).
Most users need this option. If you use the library without Spring, see
[Configuration without Spring](#configuration-without-spring) at the end of the page.

---

## ruslo

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `database-dialect` | `DatabaseDialect` | detected automatically | Explicit SQL dialect: `postgresql` or `h2` |

If the property is not set, `ProcessEngineBuilder.build()` detects the dialect once from JDBC metadata.
An explicit dialect skips the detection.

---

## ruslo.history

| Property | Type    | Default | Description                                                     |
|----------|---------|---------|-----------------------------------------------------------------|
| `enabled` | boolean | `true` | Store the history of process state changes in `xs_process_state_hist` |

---

## ruslo.database-migration

| Property  | Type    | Default | Description                                              |
|-----------|---------|---------|----------------------------------------------------------|
| `enabled` | boolean | `true`  | Run the built-in database schema migration on engine start |

The migration creates the tables `xs_schema_version`, `xs_process_instance`, `xs_process_state_hist`,
`xs_application_instance` and supports PostgreSQL and H2. If the schema is managed by Flyway, Liquibase or a DBA,
disable the property and apply the SQL files from the `ruslo-core` jar: `db/migration/<database>/V<version>__<name>.sql`.

---

## ruslo.dispatcher

| Property          | Type     | Default | Description                                                                                 |
|-------------------|----------|---------|---------------------------------------------------------------------------------------------|
| `enabled`         | boolean  | `true`  | Start the dispatcher and node registration on engine start; if disabled, the node does not run processes in the background |
| `min-interval`    | Duration | `100ms` | Minimum interval between polls of the process table (used when there is work)              |
| `max-interval`    | Duration | `10s`   | Maximum interval between polls of the process table (used when there is no work)          |
| `instance-heartbeat-interval` | Duration | `15s` | How often the node confirms it is alive                                         |
| `instance-timeout` | Duration | `60s` | How long the node may miss heartbeats before its processes are handed to other nodes       |

`instance-timeout` sets how soon after a node failure other nodes take its processes.
It must be at least four `instance-heartbeat-interval`. Details: [execution.md](execution.md#concurrent-access).

Timers usually do not depend on `max-interval`: before pausing, the dispatcher reads the due time of the nearest
process from the database and wakes up for it. `max-interval` bounds the delay only if the timer was scheduled on
another node while this node was sleeping, and that node cannot take it itself (e.g. it has no such worker).

---

## ruslo.workers.\<name\>

The section declares one or more workers that run process steps on virtual threads.
The `<name>` key becomes the worker id (`WorkerId`).

> **Required:** a worker with id `DEFAULT` is either declared explicitly or created automatically
> (transactional, `max-concurrency: 10`). To override the default worker in YAML, use the `DEFAULT` key:
> it matches `WorkerId.DEFAULT`. The lowercase `default` key is an alias for `DEFAULT`, but `DEFAULT` and `default`
> cannot be set together.
> `DEFAULT` must be `transactional`: all message and timer handlers run on it.
> `non_transactional` is available only to named workers.

| Property          | Type    | Default         | Description |
|-------------------|---------|-----------------|-------------|
| `execution-mode`  | Enum    | `transactional` | Step execution mode: `transactional` or `non_transactional` (case and separator do not matter) |
| `max-concurrency` | Integer | `10`            | Maximum number of steps running at the same time |

Mode guarantees: [guarantees.md](guarantees.md#worker-modes); assigning workers to steps: [workers.md](workers.md).

---

## ruslo.logging.mdc

The engine puts only technical process ids into MDC: correlation id, process id and type.
The current process state is not put into MDC.

| Property             | Type   | Default          | Description                   |
|----------------------|--------|------------------|-------------------------------|
| `correlation-id-key` | String | `correlation_id` | MDC key for the correlation id |
| `process-id-key`     | String | `process_id`     | MDC key for the process id    |
| `process-type-key`   | String | `process_type`   | MDC key for the process type  |

---

## Minimal configuration

A working `application.yml` with a data source and one default worker:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: myuser
    password: secret

ruslo:
  # Optional: without it, the dialect is detected from JDBC metadata.
  database-dialect: postgresql

  history:
    enabled: true

  workers:
    DEFAULT:
      execution-mode: transactional
      max-concurrency: 10
```

---

## Extended configuration

`application.yml` with two workers and explicit dispatcher settings:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: myuser
    password: secret

ruslo:
  history:
    enabled: true

  database-migration:
    enabled: true

  dispatcher:
    enabled: true
    min-interval: 50ms
    max-interval: 5s
    instance-heartbeat-interval: 15s
    instance-timeout: 60s

  workers:
    DEFAULT:
      execution-mode: transactional
      max-concurrency: 10
    io-worker:
      execution-mode: non_transactional
      max-concurrency: 50
```

The `io-worker` worker is for steps with long I/O (HTTP requests, external service calls).
Workers are assigned to steps in the `ProcessDefinition`; see [workers.md](workers.md).

The engine shares the `DataSource` and transaction manager with the application. Limit concurrent step execution
with the workers' `max-concurrency`, taking into account the size of the shared connection pool and the application
load.

---

## Configuration without Spring

`ruslo-core` does not depend on Spring and can be used in any Java application.
`Database` is an interface from `ruslo-core`; the Spring starter provides the `SpringJdbcDatabase` implementation
automatically. Without Spring, provide a `Database` implementation yourself.

A working standalone example with `PlainJdbcDatabase` and manual registration of `WorkerSpec` and
`ProcessDefinition` is in `ruslo-examples/standalone-core`:

```bash
./mvnw test -pl ruslo-examples/standalone-core -am
```

### Example

```java
// Database is an interface from ruslo-core.
// The Spring starter creates the SpringJdbcDatabase implementation automatically.
// Standalone: implement Database yourself or use PlainJdbcDatabase from ruslo-core.
Database database = new MyJdbcDatabase(dataSource);

ProcessEngine engine = new ProcessEngineBuilder(database)
        .addProcessDefinition(new OrderProcessDefinition())
        .registerWorker(WorkerSpec.builder(WorkerId.DEFAULT)
                .transactional()
                .maxConcurrency(10)
                .build())
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .withDatabaseMigrationEnabled(true)
        .withDispatcherEnabled(true)
        .withDatabaseDialect(DatabaseDialect.H2)
        .withMinProcessDispatchInterval(Duration.ofMillis(100))
        .withMaxProcessDispatchInterval(Duration.ofSeconds(10))
        .withInstanceHeartbeatInterval(Duration.ofSeconds(15))
        .withInstanceTimeout(Duration.ofSeconds(60))
        .withHistoryEnabled(true)
        .withJsonMapper(myObjectMapper) // optional: ObjectMapper for variables and messages
        .build();
engine.start();
```

A `ProcessDefinition` can be passed to the builder before `build()` or registered in an already built engine:

```java
ProcessEngine engine = new ProcessEngineBuilder(database).build();
// process steps may depend on objects that need the engine
engine.registerDefinition(new OrderProcessDefinition(stepsThatUse(engine)));
engine.start();
```

Register definitions before `start()`: the dispatcher takes ready processes of any type, and a process of an
unregistered type cannot run. Registration after `start()` is allowed.
Registering the same process type or the same definition class twice, or referencing an unregistered worker, throws
`IllegalArgumentException`; a definition cannot be removed or replaced.

`newProcess`, `sendMessage`, `sendMessageSync` and `resumeFailedProcess` are available only after `start()`; before
that they throw `IllegalStateException`.

### Builder methods and Spring properties

| `ProcessEngineBuilder` method              | Spring property                                 |
|-------------------------------------------|-------------------------------------------------|
| `withDatabaseMigrationEnabled(boolean)` | `ruslo.database-migration.enabled` |
| `withDatabaseDialect(DatabaseDialect)` | `ruslo.database-dialect` |
| `withDispatcherEnabled(boolean)` | `ruslo.dispatcher.enabled`          |
| `withMinProcessDispatchInterval(Duration)` | `ruslo.dispatcher.min-interval`        |
| `withMaxProcessDispatchInterval(Duration)` | `ruslo.dispatcher.max-interval`        |
| `withInstanceHeartbeatInterval(Duration)`  | `ruslo.dispatcher.instance-heartbeat-interval` |
| `withInstanceTimeout(Duration)`            | `ruslo.dispatcher.instance-timeout`    |
| `withHistoryEnabled(boolean)`             | `ruslo.history.enabled`                 |
| `withCorrelationIdMdcKey(String)`         | `ruslo.logging.mdc.correlation-id-key`  |
| `withProcessIdMdcKey(String)`             | `ruslo.logging.mdc.process-id-key`      |
| `withProcessTypeMdcKey(String)`           | `ruslo.logging.mdc.process-type-key`    |
| `registerWorker(WorkerSpec)`              | `ruslo.workers.<name>.*` (worker execution mode and limits) |
| `withJsonMapper(ObjectMapper)`            | none                                            |

Creating a `WorkerSpec`:

```java
WorkerSpec.builder(WorkerId.DEFAULT)
    .transactional()
    .maxConcurrency(10)
    .build()
```

If you need a dedicated platform-thread executor or integration with an external scheduler, use
`.customExecutor(executorService)`. Ruslo limits it by `maxConcurrency` but does not shut it down:
the application manages the lifecycle of an external executor. The application is also responsible for that
executor's internal queue; for a strict link between `maxConcurrency` and work actually started, use a bounded
executor or one with direct handoff.

Unknown engine Spring properties fail the startup, so a typo is not silently ignored.
The `ruslo.admin-ui.*` section is handled by the admin UI module when it is enabled.
