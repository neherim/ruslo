# Operations

English | [Русский](ru/operations.md)

## Before production

- **Database.** Use PostgreSQL in production; H2 is supported for tests and local development.
- **Schema.** By default the engine creates and updates the `xs_*` tables on startup.
  If the schema is managed by Flyway, Liquibase or a DBA, disable `ruslo.database-migration.enabled`
  and apply the SQL files from the `ruslo-core` jar: `db/migration/postgresql/V<version>__<name>.sql`.
- **Connections.** The engine shares the database connection pool with the application. A transactional step holds
  a connection for its whole execution; take this into account when sizing the pool and the workers'
  `max-concurrency`.
- **Monitoring.** The main problem signal is the `ruslo.worker.lag` metric, see [metrics](metrics.md).
- **The Admin UI** is not protected by itself: secure `/ruslo/**` with Spring Security rules,
  see the [module README](../ruslo-admin-ui/README.md#spring-security).

## Process search

```java
List<ProcessInstanceSummary> failed = processEngine.processes()
        .byTypeName("ORDER")
        .byStatus(ProcessStatus.FAILED)
        .list(100, 0);

Optional<ProcessInstanceDetails> order = processEngine.processes().byBusinessKey("order-42").singleDetails();
```

`ProcessInstanceDetails` contains all fields of `ProcessInstanceSummary` plus variables, the last error and the
state history. Variables are read in a typed way: `details.variables().get(type)` returns an `Optional` of the
variable class, `details.variables().asJsonMap()` returns the JSON text of the values.
The last error format is described in [error-handling.md](error-handling.md#guarantees).
The message queue is not visible through the read API.

## Manual intervention

| Operation | What it does |
|---|---|
| `processEngine.resumeFailedProcess(id)` | Resumes a process from `FAILED`: the failed step or event runs again; `ProcessStatusException` if the process is not in `FAILED` (active or completed) |
| `processEngineAdmin.moveProcessToState(id, state)` | Moves the process to any declared state, including from `FAILED`; the message queue and the error are cleared, entering a terminal state completes the process |
| `processEngineAdmin.setProcessVariable(id, type, value)` | Changes a process variable |
| `processEngineAdmin.removeProcessVariable(id, name)` | Removes a process variable |
| `processEngineAdmin.countIncompleteProcessesByType(overdueAt)` | Number of processes in `ACTIVE` and `FAILED` per type and number of overdue ones: in `ACTIVE` with `scheduledAt` not later than `overdueAt` |
| `processEngineAdmin.listApplicationInstances()` | Registered engine instances, the number of processes they have claimed and the registration expiry |

`processEngineAdmin` is a `ProcessEngineAdmin` instance: in Spring Boot it is a ready bean; without Spring it is
created from the engine (`new ProcessEngineAdmin(processEngine)`). `ProcessEngine` does not expose admin operations.

A process in `FAILED` keeps accepting messages of its await (`sendMessage`): they are handled after `resumeFailedProcess`.

The same operations are available in the [Admin UI](admin-ui.md).

## Retention of completed processes

The engine does not delete completed processes: `xs_process_instance` rows with status `COMPLETED` and their history
in `xs_process_state_hist` stay in the database. Delete them yourself on a schedule; the history is deleted by cascade:

```sql
delete from xs_process_instance
where status = 'COMPLETED' and update_at < now() - interval '90 days';
```

If you do not need the state history, disable it with `ruslo.history.enabled=false`.
