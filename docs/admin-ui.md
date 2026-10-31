# Admin UI

English | [Русский](ru/admin-ui.md)

A web UI for viewing processes and manual intervention. It is disabled by default; enable it with
`ruslo.admin-ui.enabled=true`. Installation, settings and protection with Spring Security are in the
[module README](../ruslo-admin-ui/README.md).

## Pages

- **Dashboard** (`/ruslo`): the number of `FAILED`, overdue and active processes, overall and per type, and the
  engine nodes with a running dispatcher. A process is overdue if it is active and its next run passed more than a
  minute ago: no node is picking it up. A counter opens the matching process list. A process type is shown with
  the description set by `ProcessSpecBuilder.description(...)`.
- **Process list** (`/ruslo/processes`): search with filters and starting a new process.
- **Process page**: status, state, variables, process timers, last error, state history and a diagram of the
  definition with the path taken. The page refreshes while the process is running.

## Actions

The Actions menu of the process page shows every action; one that does not apply to the process is disabled with the
reason. The actions are the [manual intervention operations](operations.md#manual-intervention):

| Action | Operation |
|---|---|
| Retry current step | `resumeFailedProcess`, for a process in `FAILED` |
| Change state | `moveProcessToState` |
| Send message | `sendMessage`, with a JSON payload checked against the payload class |
| Add, edit, remove variable | `setProcessVariable`, `removeProcessVariable` |
| Set, cancel timer | `setProcessTimer`, `cancelProcessTimer`; the moment is in the browser time zone |

Every action is written to the application log with the user name.

## Process diagram

The diagram is built from the definition: states are labeled with `ProcessState.description()` (the state name by default), edges with the
outcome, the message name or the timer. The legend is in the [module README](../ruslo-admin-ui/README.md#process-diagram).
