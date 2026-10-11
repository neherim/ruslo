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
- `every`: the scheduled check time;
- `timeout`: the deadline.

Among due events, the one with the smallest time is handled. On a tie the order is: message, `every`, `timeout`;
messages with the same time go in arrival order. The moment the dispatcher reached the process does not affect the
choice. The only exception: if the process is waiting to retry a failed event, a due deadline wins, so a handler
error does not extend the await.

Example (deadline at 12:00):

| Situation | Result |
|---|---|
| Message accepted at 11:59, handling started at 12:01 | the message is handled |
| Message accepted at 11:59, its handling failed and was scheduled for retry at 12:05 | `timeout` fires at 12:00 |
| Message accepted at 11:30, its handling failed and was scheduled for retry at 11:35 | the message is retried at 11:35 |
| Message accepted at 11:59, handled at 12:01 with the await itself as the target | `timeout` fires next |
| Message accepted at 12:00:30, handling started at 12:01 | `timeout` fires, the message is removed with the queue |

Consequences:

- a message accepted after the deadline but before it is handled is removed on the `timeout` transition,
  although `sendMessage` returned successfully;
- a late `every` sees the current state of the external system: a check scheduled for 11:58
  and run at 12:01 may see a change made after the deadline;
- `every` checks missed during downtime are not run in a batch: one runs, and the next is scheduled from the
  moment of handling;
- timers do not fire in status `FAILED`; after `resumeFailedProcess` the event is selected by these rules again.

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
- The message bypasses the queue: earlier messages and due `every`/`timeout` are ignored, and on a transition to
  another state the queued messages are removed unhandled. If the process is waiting to retry a failed event, that
  retry is kept.
- Applicability is checked as in `sendMessage`: if the process is not waiting for the message now or has completed,
  the method throws `MessageNotExpectedException`. A process in `FAILED` is not handled synchronously:
  `ProcessStatusException`, the message is not stored; `resumeFailedProcess` is needed first.
- A handler exception propagates to the caller and the transaction rolls back: the message is not stored, no error
  is recorded in the process, and `ProcessErrorHandler` is not called.
