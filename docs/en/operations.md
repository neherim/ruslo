# Operations

## Before production

- **Database.** Use PostgreSQL in production; H2 is for tests and local runs.
- **Schema.** The engine creates and updates its `xs_*` tables on start. To manage the schema yourself, see
  [configuration](configuration.md#database-schema).
- **Connections.** The engine shares the connection pool with the application, and a transactional step holds a
  connection while it runs. Size the pool and the workers' `max-concurrency` together.
- **Monitoring.** Watch the `ruslo.worker.lag` metric and the number of `FAILED` processes, see [metrics](metrics.md).
- **Admin UI.** It is not protected by itself: secure `/ruslo/**` with Spring Security, see the
  [module README](https://github.com/neherim/ruslo/blob/main/ruslo-admin-ui/README.md#spring-security).
- **Retention.** Plan the cleanup of completed processes, see [below](#retention-of-completed-processes).

## Process search

```java
List<ProcessInstanceSummary> failed = processEngine.processes()
        .byTypeName("ORDER")
        .byStatus(ProcessStatus.FAILED)
        .list(100, 0);

Optional<ProcessInstanceDetails> order = processEngine.processes().byBusinessKey("order-42").singleDetails();
```

`ProcessInstanceDetails` adds variables, process timers, the last error and the state history to the summary.
Read variables in a typed way: `details.variables().get(ORDER_ID)` returns an `Optional`. `nextRunAt` is when the
engine next takes the process; it is empty if the process waits only for messages, is in `FAILED` or completed.

## Manual intervention

| Operation | What it does |
|---|---|
| `processEngine.resumeFailedProcess(id)` | Continues a process in `FAILED`: the failed step or event runs again |
| `processEngineAdmin.moveProcessToState(id, state)` | Moves the process to any declared state, also from `FAILED`, and clears the error |
| `processEngineAdmin.setProcessVariable(id, type, value)`, `removeProcessVariable(id, name)` | Changes process variables |
| `processEngineAdmin.setProcessTimer(id, timer, moment)`, `cancelProcessTimer(id, timer)` | Changes a process timer, as `setTimer` and `cancelTimer` in a handler; `Instant.now()` fires `at` right away |
| `processEngineAdmin.countIncompleteProcessesByType(overdueAt)` | Active and failed processes per type, and how many are overdue |
| `processEngineAdmin.listApplicationInstances()` | Registered engine nodes and the processes they have claimed |

A move follows the transition rules: a move to another state clears the message queue, a move to the current state
runs the step or the due event again, a move to a terminal state completes the process.

In Spring Boot, `ProcessEngineAdmin` is a bean; without Spring, create it with `new ProcessEngineAdmin(processEngine)`.
The same operations are available in the [Admin UI](admin-ui.md).

## Editing a process in SQL

Prefer `ProcessEngineAdmin`: it keeps the process row consistent. The columns of `xs_process_instance` are internal
and may change between versions. If you still have to change a row by hand during an incident:

- change only rows with `lease_owner is null` and always increment `row_version`, otherwise a running worker may
  overwrite the change;
- set `next_run_at = current_timestamp` after the change, so the engine takes the process and recomputes its timers.

```sql
update xs_process_instance
set next_run_at = current_timestamp, row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;
```

An `update` that changed no rows hit a claimed process: repeat it.

## Retention of completed processes

The engine does not delete completed processes. Delete them on a schedule; their history is deleted by cascade:

```sql
delete from xs_process_instance
where status = 'COMPLETED' and updated_at < now() - interval '90 days';
```

If you do not need the state history, disable it with `ruslo.history.enabled=false`.
