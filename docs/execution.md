# Execution and transaction boundaries

English | [Русский](ru/execution.md)

The summary of guarantees is in [guarantees.md](guarantees.md). This page has the exact rules for edge cases.

## Transitions

Every handler call ends with one transaction that saves the new state and the `ProcessContext` changes. The next
handler runs in a new transaction. If the next state runs on the same worker, the same node continues the process
without claiming it again; otherwise the process returns to the shared queue.

If a process starts in an await, the start saves the process and schedules timers; no user code runs.

A handler exception rolls back its transaction: variable changes are not saved, and the selected message or the due
timer stays for a retry. The [error policy](error-handling.md) applies next.

## Await event selection

One run of an await handles **at most one** event. Every event has a time:

- a message: the moment `sendMessage` stored it;
- `every`: the planned grid point, `entering the await + initialDelay + k * interval`;
- `after`: entering the await plus `delay`, computed on entering;
- `at`: the value of its variable.

An event is due when its time is not later than the engine clock. Among due events, the one with the smallest time
is handled. On a tie the order is: message, `every`, `after`, `at`; messages with the same time go in arrival order.
The moment the dispatcher reached the process does not affect the choice.

**Timer moments.** After a successful `every` the next check is the first grid point after the handling. After a
successful `after` the timer is off until the await is entered again. The `at` timer is set to the value of its
variable on entering the await and on every write of a new value in the await (a handler, `setProcessVariable` in
the admin API); removing the variable turns it off. After a successful `at` the timer is off until the variable gets
a new value; a new value written by the `at` handler itself sets the next moment. Writing the same value does not
set the timer again, so a handler that does not move the moment does not make a loop.

A moment in the past or equal to now is an event that is already due, selected by the usual rules: the process
entered the await after the deadline, a message moved the deadline into the past, or dates were missed during
downtime (the `at` handler can skip past dates itself). A variable used by `at` in any await of the process must be
an `Instant` in every state. A value that cannot be read as `Instant` is an error of the writer: the write throws
`IllegalArgumentException` and the process is unchanged. From a handler or step it is a handler error, retried by
[`onError`](error-handling.md); from `setProcessVariable` or from the start variables the operation throws.

**Retry backoff.** A handler error pauses the whole await: until the retry time the process does not run, and new
messages, due timers and writes of the `at` variable do not wake it. After the pause the event is selected by the
rules above, usually the failed one: its time is earlier than the others. An error postpones handling but does not
change the order of events, so the result does not depend on temporary failures. A failed `every`, `after` or `at`
handler is retried by [`onError`](error-handling.md); the timer stays on until it is handled successfully.

The cost: if a message accepted at 11:59 keeps failing, a deadline at 12:00 does not fire until the message is
handled or the process leaves the await by its error policy; with unlimited retries it never fires. For a hard
deadline, limit the retries (`ProcessErrorHandler.fixed(maxRetries, delay)` with a `fallbackState`) or catch the
error in the handler and return an outcome that keeps the await waiting.

Example (`after(1h)` from entering the await at 11:00, so the deadline is 12:00):

| Situation | Result |
|---|---|
| Message accepted at 11:59, handling started at 12:01 | the message is handled |
| Message accepted at 11:59, its handling failed and was scheduled for retry at 12:05 | the message is retried at 12:05, `after` waits for it |
| Message accepted at 11:59, handling at 12:01 failed and was scheduled for retry at 12:06 | the message is retried at 12:06, `after` waits for it |
| Message accepted at 11:30, its handling failed and was scheduled for retry at 11:35 | the message is retried at 11:35 |
| Message accepted at 11:59, handled at 12:01 with the await itself as the target | `after` fires right after it |
| Message accepted at 12:00:30, handling started at 12:01 | `after` fires, the message is removed with the queue |
| `after` handler failed at 12:00 and was scheduled for retry at 12:05, `at` is 12:02 | `after` is retried at 12:05, `at` waits for it |
| A message accepted at 11:30 failed and was scheduled for retry at 11:35; `at` was moved from 13:00 to 11:32 | the message is retried at 11:35 |

Consequences:

- a message accepted after the deadline but before it is handled is removed on the deadline transition,
  although `sendMessage` returned successfully;
- a late `every` sees the current state of the external system: a check scheduled for 11:58
  and run at 12:01 may see a change made after the deadline;
- `every` checks missed during downtime are not run in a batch: one runs, and the next is the first point of the
  grid after it is handled;
- a late check does not shift the grid, so the check after it may come sooner than `interval`: there is no minimum
  interval between checks. With `every(10m)` and a deadline at 12:00, the 12:00 check runs before `after`
  however late the previous checks were;
- timers do not fire in status `FAILED`, and a write of the `at` variable does not wake such a process; after
  `resumeFailedProcess` the event is selected by these rules again.

A process taken with no due event, for example after `next_run_at` was changed by hand
([operations.md](operations.md#editing-timers-in-sql)), only recomputes its next run: no handler runs, no attempt is
counted and no history row is written.

## Concurrent access

One node claims a process. A transactional worker holds the process row lock while the handler runs,
so `sendMessage` for this process waits for the transition to finish and sees either the old await or the already
committed new state. A non-transactional worker does not hold the lock: before saving the result, the engine checks
that the process still belongs to this node and has not changed; otherwise the result is discarded and the step
runs again.

If a node has not renewed its registration for longer than `ruslo.dispatcher.instance-timeout`, its processes are
released and taken by other nodes. The timeout is measured by database time, so node clock divergence does not
affect it. A node that comes back after such a pause registers again, and the results of steps it had started are
discarded.

## Application transactions in Spring

Engine operations join the active application transaction (`PROPAGATION_REQUIRED`). So `newProcess(...).start()` and
`sendMessage()` inside `@Transactional` are atomic with the application changes on the same `DataSource` and
transaction manager: on rollback neither is saved. The dispatcher wakes up after commit. Process steps run later,
each transition in its own transaction.

Engine errors (database errors, `ProcessNotFoundException`, an undeclared message type) are thrown from inside its
transaction and, as usual in Spring, mark the application transaction rollback-only even if caught.
The engine throws expected race outcomes after leaving its transaction, so by themselves they do not mark the
transaction: if the process is not waiting for the message now, `sendMessage` and `sendMessageSync` throw
`MessageNotExpectedException`, and if the process is not in `FAILED`, `resumeFailedProcess` throws
`ProcessStatusException`. A caught rejection does not prevent the application transaction from committing; an
uncaught one rolls it back, like any exception.

## Synchronous message handling

`processEngine.sendMessageSync(processId, type, payload)` runs the message handler in the calling thread,
in the active application transaction or in a new one. The process row stays locked until commit or rollback.

- Only the handler of this message runs synchronously, not the process until it stops; the dispatcher runs the
  following steps after commit.
- The message bypasses the queue: earlier messages and due `every`, `after` and `at` are ignored, and on a transition to
  another state the queued messages are removed unhandled. If the process is waiting to retry a failed event, that
  retry is kept.
- Applicability is checked as in `sendMessage`: if the process is not waiting for the message now or has completed,
  the method throws `MessageNotExpectedException`. A process in `FAILED` is not handled synchronously: the same
  `MessageNotExpectedException` with `getStatus() == FAILED`, the message is not stored; `resumeFailedProcess` is
  needed first.
- A handler exception propagates to the caller and the transaction rolls back: the message is not stored, no error
  is recorded in the process, and `ProcessErrorHandler` is not called.
