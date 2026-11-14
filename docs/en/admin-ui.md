# Admin UI

A web UI for viewing processes and manual intervention. It is disabled by default; enable it with
`ruslo.admin-ui.enabled=true`. Installation, settings and protection with Spring Security are in the
[module README](https://github.com/neherim/ruslo/blob/main/ruslo-admin-ui/README.md).

## Pages

- **Dashboard** (`/ruslo`): the number of `FAILED`, overdue and active processes, overall and per type, and the
  engine nodes with a running dispatcher. A process is overdue if it is active and its next run passed more than a
  minute ago: no node is picking it up. A counter opens the matching process list. A process type is shown with
  the description set by `ProcessSpecBuilder.description(...)`.
- **Process list** (`/ruslo/processes`): search with filters and bulk actions across all matching processes.
- **Process page**: status, state, variables, process timers, last error, state history and a diagram of the
  definition with the path taken. The page refreshes while the process is running.

## Actions

The Actions menu of the process page shows all manual actions available for the process:

| Action | Operation |
|---|---|
| Retry current step | `resumeFailedProcess`, for a process in `FAILED` |
| Change state | `moveProcessToState` |
| Send message | `sendMessage`, with a JSON payload checked against the payload class |
| Add, edit, remove variable | `setProcessVariable`, `removeProcessVariable` |
| Set, cancel timer | `setProcessTimer`, `cancelProcessTimer`; the moment is in the browser time zone |

Every action is written to the application log with the user name.

## Bulk actions

The process list's **Bulk actions** menu applies the last submitted search filters to **all pages**:

- **Retry all matching** requires the `FAILED` status filter. It resumes processes for the dispatcher;
  it does not wait for their handlers to finish.
- **Change state for all matching** requires one registered process type. The target is a state declared
  by that type, including terminal states. Completed processes are skipped.

Before confirmation, the dialog shows the exact process count, applied filters, a breakdown by type/state/status
and up to twenty example rows. Edited filters do not take effect until Search is submitted. The confirmed
selection is fixed: new matches are not added. A process whose state or status changed, or which no longer
matches the filter, is skipped after a check under its row lock.

There is no application limit on the process count. The operation runs sequentially with a waiting indicator
and commits each process separately. An unexpected error stops it; earlier successful changes stay committed.
The dialog reports successes, skips with reasons, and any interrupted process whose outcome needs checking.
If the HTTP response is lost, the server may still be running the operation: check the processes before
starting another one. The browser does not retry automatically.

Bulk actions use the engine's `Database` bean. The standard starter supplies it; manual engine setup and
deployment limits for long requests are described in the
[module README](https://github.com/neherim/ruslo/blob/main/ruslo-admin-ui/README.md#bulk-operations).

## Process diagram

The diagram is built from the definition: states are labeled with `ProcessState.description()` (the state name by default), edges with the
outcome, the message name or the timer. The legend is in the [module README](https://github.com/neherim/ruslo/blob/main/ruslo-admin-ui/README.md#process-diagram).

Branching transitions can have a label declared with `routes.on(outcome, target, "Label")`, in steps and in await
events. The diagram uses this label instead of the outcome name; message names and timers remain visible.
Without a label, the outcome name is shown.

```java
.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING, "Validation passed")
    .on(Validation.REJECTED, CANCELLED, "Validation failed"))
```
