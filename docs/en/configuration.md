# Configuration

The engine is configured with `ProcessEngineBuilder`. In Spring Boot the starter builds it and every setting is a
`ruslo.*` property: [spring.md](spring.md).

## Engine without Spring

`ruslo-core` does not depend on Spring; a minimal setup needs a `Database` and a process definition. Use the
bundled `PlainJdbcDatabase` or your own implementation:

```java
ProcessEngine engine = new ProcessEngineBuilder(new PlainJdbcDatabase(dataSource))
        .addProcessDefinition(new OrderProcessDefinition())
        .build();
engine.start();
```

`engine.start()` runs the database schema migration and starts the process dispatcher.
Register every `ProcessDefinition` before `start()`: a process of an unregistered type cannot run.
If process steps need the `ProcessEngine`, build it first, then register your `ProcessDefinition`s with
`engine.registerDefinition(...)` and start the engine with `start()`.

A working example: [`ruslo-examples/standalone-core`](https://github.com/neherim/ruslo/tree/main/ruslo-examples/standalone-core).

## Settings

| `ProcessEngineBuilder` method | Spring property | Default | Description |
|---|---|---|---|
| `withDatabaseMigrationEnabled(boolean)` | `ruslo.database-migration.enabled` | `true` | Create and update the engine tables on start |
| `withHistoryEnabled(boolean)` | `ruslo.history.enabled` | `true` | Store the history of state changes in `xs_process_state_hist` |
| `withDispatcherEnabled(boolean)` | `ruslo.dispatcher.enabled` | `true` | Run processes in the background on this node |
| `withMinProcessDispatchInterval(Duration)` | `ruslo.dispatcher.min-interval` | `100ms` | Initial polling backoff after an empty cycle; at least `1ms`, no greater than the maximum |
| `withMaxProcessDispatchInterval(Duration)` | `ruslo.dispatcher.max-interval` | `10s` | Maximum polling backoff when there is no work |
| `withInstanceHeartbeatInterval(Duration)` | `ruslo.dispatcher.instance-heartbeat-interval` | `15s` | How often the node confirms it is alive |
| `withInstanceTimeout(Duration)` | `ruslo.dispatcher.instance-timeout` | `60s` | After how long without a heartbeat other nodes take the node's processes; at least four heartbeat intervals |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.execution-mode` | `transactional` | `transactional` or `non_transactional` |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.max-concurrency` | `10` | Maximum number of steps of the worker running at the same time on this node |
| `withCorrelationIdMdcKey(String)` | `ruslo.logging.mdc.correlation-id-key` | `correlation_id` | MDC key for the correlation id |
| `withProcessIdMdcKey(String)` | `ruslo.logging.mdc.process-id-key` | `process_id` | MDC key for the process id |
| `withProcessTypeMdcKey(String)` | `ruslo.logging.mdc.process-type-key` | `process_type` | MDC key for the process type |
| `withMetrics(ProcessEngineMetrics)` | none | no-op | In Spring, Micrometer is wired automatically: [metrics.md](metrics.md) |

The Admin UI properties (`ruslo.admin-ui.*`) are described in its [module README](https://github.com/neherim/ruslo/blob/main/ruslo-admin-ui/README.md).

## Database schema

If the schema is managed by Flyway, Liquibase or a DBA, disable the migration and apply the SQL files from the
`ruslo-core` jar: `db/migration/<database>/V<version>__<name>.sql`.

## Workers

The `<name>` key in `ruslo.workers.<name>.*` is the worker id. How to declare a worker, reconfigure `DEFAULT` and
choose `max-concurrency`: [workers.md](workers.md#registration).
