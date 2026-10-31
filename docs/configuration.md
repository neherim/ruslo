# Configuration

English | [Русский](ru/configuration.md)

The engine is configured with `ProcessEngineBuilder`. In Spring Boot the starter builds it and every setting is a
`ruslo.*` property: [spring.md](spring.md).

## Engine without Spring

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

## Settings

| `ProcessEngineBuilder` method | Spring property | Default | Description |
|---|---|---|---|
| `withDatabaseMigrationEnabled(boolean)` | `ruslo.database-migration.enabled` | `true` | Create and update the engine tables on start |
| `withHistoryEnabled(boolean)` | `ruslo.history.enabled` | `true` | Store the history of state changes in `xs_process_state_hist` |
| `withDispatcherEnabled(boolean)` | `ruslo.dispatcher.enabled` | `true` | Run processes in the background on this node |
| `withMinProcessDispatchInterval(Duration)` | `ruslo.dispatcher.min-interval` | `100ms` | Interval between polls of the process table when there is work |
| `withMaxProcessDispatchInterval(Duration)` | `ruslo.dispatcher.max-interval` | `10s` | Interval between polls when there is no work |
| `withInstanceHeartbeatInterval(Duration)` | `ruslo.dispatcher.instance-heartbeat-interval` | `15s` | How often the node confirms it is alive |
| `withInstanceTimeout(Duration)` | `ruslo.dispatcher.instance-timeout` | `60s` | After how long without a heartbeat other nodes take the node's processes; at least four heartbeat intervals |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.execution-mode` | `transactional` | `transactional` or `non_transactional` |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.max-concurrency` | `10` | Maximum number of steps of the worker running at the same time on this node |
| `withCorrelationIdMdcKey(String)` | `ruslo.logging.mdc.correlation-id-key` | `correlation_id` | MDC key for the correlation id |
| `withProcessIdMdcKey(String)` | `ruslo.logging.mdc.process-id-key` | `process_id` | MDC key for the process id |
| `withProcessTypeMdcKey(String)` | `ruslo.logging.mdc.process-type-key` | `process_type` | MDC key for the process type |
| `withJsonMapper(ObjectMapper)` | none | | A custom `ObjectMapper` for variables and messages |
| `withMetrics(ProcessEngineMetrics)` | none | no-op | In Spring, Micrometer is wired automatically: [metrics.md](metrics.md) |

The Admin UI properties (`ruslo.admin-ui.*`) are described in its [module README](../ruslo-admin-ui/README.md).

## Database schema

If the schema is managed by Flyway, Liquibase or a DBA, disable the migration and apply the SQL files from the
`ruslo-core` jar: `db/migration/<database>/V<version>__<name>.sql`.

## Workers

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

A worker can run on your own executor: `WorkerSpec.builder(id).customExecutor(executorService)`. The engine limits
it by `maxConcurrency` but does not shut it down.
