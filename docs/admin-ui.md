# Admin UI

English | [Русский](ru/admin-ui.md)

A web UI for viewing processes and manual intervention. It is disabled by default; to enable it, set
`ruslo.admin-ui.enabled=true`. Installation, settings and protection with Spring Security are described in the
[module README](../ruslo-admin-ui/README.md).

## What you can do

- **Dashboard** (`/ruslo`): counters of `FAILED`, overdue and `ACTIVE` processes, the same counters per definition
  type, and the registered engine instances (nodes with a running dispatcher) with the number of processes they have
  claimed and the registration expiry. A process is overdue if it is in `ACTIVE` and its next run passed more
  than a minute ago, the same as on the process page. Clicking a counter opens the process list with the matching
  filter. The dashboard refreshes every 5 seconds. An empty instance list means the dispatcher is not running on any
  node.
- **Process list** (`/ruslo/processes`): filters and starting a new process.
- **Process page**: status, state with its description, variables, process timers, last error, history and a
  diagram with the path taken. The Timers tab lists the set timers and those an `at` of the current definition
  declares, with the moment in the browser time zone; a timer that no `at` of the current await waits for (also of a
  completed process) is marked "not awaited". The next run (a step, a retry, a queued message or a timer) is shown relative to now ("in 2m"); for a
  process in `ACTIVE` whose next run passed more than a minute ago, it is highlighted as overdue: the dispatcher is
  not picking the process up. Until the
  process completes, the page checks for changes every 5 seconds and re-renders only when there are any; the check is
  skipped while a dialog or menu is open and in a background tab. The history marks the current state and manual
  transitions.

Actions are in the Actions menu of the process page. The menu always shows all actions: an action unavailable for
the current process is disabled with the reason (Retry only for `FAILED`, Send message only in an await that declares
messages). The actions match the [manual intervention operations](operations.md#manual-intervention):

| Action | What it does |
|---|---|
| Retry current step | `resumeFailedProcess`: retries the failed step or event of a process in `FAILED` |
| Change state | `moveProcessToState`: moves the process to the selected state; the message queue is cleared |
| Send message | takes JSON, validates it against the payload class and stores it like `sendMessage`; available in an await that declares messages, including in `FAILED` |
| Add / edit / remove variable | changes process variables |
| Set / cancel timer | `setProcessTimer` / `cancelProcessTimer` on the Timers tab; the moment is entered in the browser time zone. Set is disabled for a timer no `at` of the current definition declares, Cancel for a timer that is not set |

Retry, variable removal and timer cancellation ask for confirmation. Every action is written to the application log with the user name.
If an action fails, the dialog stays open and shows the error.

Sending a message confirms that it is stored, not that the handler is called: the same
[guarantees](guarantees.md#messages) as for `sendMessage`. The Admin UI does not show the message queue contents or
message history.

## Process diagram

The diagram is built automatically from the definition: states are labeled with `ProcessState.description()`, edges
with the outcome, the message name, `every 30s`, `after 7d` or `at dueDate`. The process page highlights the visited states and the
current state; a manual transition outside the declared ones is drawn as a dashed line. The legend is in the
[module README](../ruslo-admin-ui/README.md#process-diagram).
