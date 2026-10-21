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

`nextRunAt` is when the engine next takes the process: a step, a retry, a queued message or a timer. It is empty if
the process waits only for messages, is in `FAILED` or completed. Search by it with `byNextRunAtFrom` /
`byNextRunAtTo`. The moments of individual timers are not in the read API: the `at` moment is the value of its
variable, and a timer that is off is visible by `nextRunAt`.

## Manual intervention

| Operation | What it does |
|---|---|
| `processEngine.resumeFailedProcess(id)` | Resumes a process from `FAILED`: the failed step or event runs again; `ProcessStatusException` if the process is not in `FAILED` (active or completed) |
| `processEngineAdmin.moveProcessToState(id, state)` | Moves the process to any declared state, including from `FAILED`; the message queue and the error are cleared, entering a terminal state completes the process |
| `processEngineAdmin.setProcessVariable(id, type, value)` | Changes a process variable; writing the `at` variable of the current await moves its timer, a value that is not an `Instant` is rejected |
| `processEngineAdmin.removeProcessVariable(id, name)` | Removes a process variable; removing the `at` variable of the current await turns its timer off |
| `processEngineAdmin.countIncompleteProcessesByType(overdueAt)` | Number of processes in `ACTIVE` and `FAILED` per type and number of overdue ones: in `ACTIVE` with `nextRunAt` not later than `overdueAt`, including an unhandled message and a due timer |
| `processEngineAdmin.listApplicationInstances()` | Registered engine instances, the number of processes they have claimed and the registration expiry |

`processEngineAdmin` is a `ProcessEngineAdmin` instance: in Spring Boot it is a ready bean; without Spring it is
created from the engine (`new ProcessEngineAdmin(processEngine)`). `ProcessEngine` does not expose admin operations.

A process in `FAILED` keeps accepting messages of its await (`sendMessage`): they are handled after `resumeFailedProcess`.

The same operations are available in the [Admin UI](admin-ui.md).

## Editing timers in SQL

While investigating an incident you may change timer columns of `xs_process_instance` directly:

| Column | Meaning |
|---|---|
| `next_run_at` | When the engine next takes the process: a step, a retry, a message or a timer; `null` if it waits only for messages, is in `FAILED` or completed |
| `every_due_at` | The next grid point of `every`; `null` for a step and an await without `every` |
| `after_due_at` | The `after` deadline; `null` if it has fired or `after` is not declared |
| `at_fired_due_at` | The `at` moment (the variable value) already handled in this entry of the await; reset on entering the await |

Two rules:

1. **The engine computes `next_run_at`** from the timer columns, the message queue and the retry. A timer change
   without a `next_run_at` change takes effect only at the old `next_run_at`. After changing timer columns, set
   `next_run_at = current_timestamp`: the engine takes the process, selects the due events and recomputes
   `next_run_at` itself. A process taken with no due event only recomputes it: no handler, no attempt, no history.
2. **Fencing.** A non-transactional worker checks `lease_owner` and `row_version` and may silently overwrite a
   change. Change only rows with `lease_owner is null`, and always with `row_version = row_version + 1`.

Recipes (the `at` deadline is changed through `setProcessVariable`, not in SQL):

```sql
-- Run now
update xs_process_instance
set next_run_at = current_timestamp, row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;

-- Extend the after deadline by a day
update xs_process_instance
set after_due_at = after_due_at + interval '1 day', next_run_at = current_timestamp,
    row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;

-- Fire at again for the current value of its variable
update xs_process_instance
set at_fired_due_at = null, next_run_at = current_timestamp, row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;

-- Move the every grid
update xs_process_instance
set every_due_at = timestamp with time zone '2026-10-21 12:00:00+00', next_run_at = current_timestamp,
    row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;
```

An `update` that changed no rows hit a claimed process: repeat it.

## Retention of completed processes

The engine does not delete completed processes: `xs_process_instance` rows with status `COMPLETED` and their history
in `xs_process_state_hist` stay in the database. Delete them yourself on a schedule; the history is deleted by cascade:

```sql
delete from xs_process_instance
where status = 'COMPLETED' and updated_at < now() - interval '90 days';
```

If you do not need the state history, disable it with `ruslo.history.enabled=false`.
