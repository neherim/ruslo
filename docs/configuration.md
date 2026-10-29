# Configuration

English | [Русский](ru/configuration.md)

The Spring Boot starter needs no `ruslo.*` settings: a `DataSource` is enough. It creates the engine tables, a
transactional `DEFAULT` worker and starts the dispatcher. This page lists the properties for when the defaults do not
fit. Without Spring, see [Configuration without Spring](#configuration-without-spring).

Unknown `ruslo.*` properties fail the startup, so a typo is not silently ignored.

## Properties

| Property | Default | Description |
|---|---|---|
| `ruslo.database-dialect` | detected from JDBC metadata | SQL dialect: `postgresql` or `h2` |
| `ruslo.database-migration.enabled` | `true` | Create and update the engine tables on start |
| `ruslo.history.enabled` | `true` | Store the history of state changes in `xs_process_state_hist` |
| `ruslo.dispatcher.enabled` | `true` | Run processes in the background on this node |
| `ruslo.dispatcher.min-interval` | `100ms` | Interval between polls of the process table when there is work |
| `ruslo.dispatcher.max-interval` | `10s` | Interval between polls when there is no work |
| `ruslo.dispatcher.instance-heartbeat-interval` | `15s` | How often the node confirms it is alive |
| `ruslo.dispatcher.instance-timeout` | `60s` | After how long without a heartbeat other nodes take the node's processes; at least four heartbeat intervals |
| `ruslo.workers.<name>.execution-mode` | `transactional` | `transactional` or `non_transactional` |
| `ruslo.workers.<name>.max-concurrency` | `10` | Maximum number of steps of the worker running at the same time on this node |
| `ruslo.logging.mdc.correlation-id-key` | `correlation_id` | MDC key for the correlation id |
| `ruslo.logging.mdc.process-id-key` | `process_id` | MDC key for the process id |
| `ruslo.logging.mdc.process-type-key` | `process_type` | MDC key for the process type |

The Admin UI properties (`ruslo.admin-ui.*`) are described in its [module README](../ruslo-admin-ui/README.md).

### Database schema

If the schema is managed by Flyway, Liquibase or a DBA, set `ruslo.database-migration.enabled=false` and apply the
SQL files from the `ruslo-core` jar: `db/migration/<database>/V<version>__<name>.sql`.

### Workers

The `<name>` key is the worker id. The `DEFAULT` worker always exists; declare it under the `DEFAULT` key to change
its `max-concurrency`. It must stay `transactional`, because message and timer handlers run on it.

```yaml
ruslo:
  workers:
    DEFAULT:
      max-concurrency: 20
    io-worker:
      execution-mode: non_transactional
      max-concurrency: 50
```

Workers share the application's connection pool: size `max-concurrency` with the pool in mind. Assigning workers to
steps: [workers.md](workers.md).

## Configuration without Spring

`ruslo-core` does not depend on Spring. Pass it a `Database`: the bundled `PlainJdbcDatabase` or your own
implementation.

```java
ProcessEngine engine = new ProcessEngineBuilder(new PlainJdbcDatabase(dataSource))
        .addProcessDefinition(new OrderProcessDefinition())
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .build();
engine.start();
```

`start()` runs the migration and starts the dispatcher; engine operations throw `IllegalStateException` before it.
Register definitions before `start()`: a process of an unregistered type cannot run. If steps need the engine
itself, build it first and register the definition with `engine.registerDefinition(...)`.

A working example: [`ruslo-examples/standalone-core`](../ruslo-examples/standalone-core).

| `ProcessEngineBuilder` method | Spring property |
|---|---|
| `withDatabaseDialect(DatabaseDialect)` | `ruslo.database-dialect` |
| `withDatabaseMigrationEnabled(boolean)` | `ruslo.database-migration.enabled` |
| `withHistoryEnabled(boolean)` | `ruslo.history.enabled` |
| `withDispatcherEnabled(boolean)` | `ruslo.dispatcher.enabled` |
| `withMinProcessDispatchInterval(Duration)` | `ruslo.dispatcher.min-interval` |
| `withMaxProcessDispatchInterval(Duration)` | `ruslo.dispatcher.max-interval` |
| `withInstanceHeartbeatInterval(Duration)` | `ruslo.dispatcher.instance-heartbeat-interval` |
| `withInstanceTimeout(Duration)` | `ruslo.dispatcher.instance-timeout` |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.*` |
| `withCorrelationIdMdcKey(String)` | `ruslo.logging.mdc.correlation-id-key` |
| `withProcessIdMdcKey(String)` | `ruslo.logging.mdc.process-id-key` |
| `withProcessTypeMdcKey(String)` | `ruslo.logging.mdc.process-type-key` |
| `withJsonMapper(ObjectMapper)` | none: a custom `ObjectMapper` for variables and messages |
| `withMetrics(ProcessEngineMetrics)` | none: Micrometer is wired automatically, see [metrics.md](metrics.md) |

A worker can run on your own executor: `WorkerSpec.builder(id).customExecutor(executorService)`. The engine limits
it by `maxConcurrency` but does not shut it down.
