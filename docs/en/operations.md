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

## Editing a process in SQL

If you still have to change a row by hand during an incident:

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
