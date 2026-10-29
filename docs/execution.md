# Execution rules

English | [Русский](ru/execution.md)

The exact rules behind [guarantees.md](guarantees.md): transaction boundaries, the order of await events and
concurrent access. You do not need this page to write ordinary processes; read it when timing between events
matters.

## Transitions

Every handler call ends with one transaction that saves the new state and the `ProcessContext` changes. The next
handler runs in a new transaction. If the next state runs on the same worker, the same node continues the process;
otherwise the process returns to the shared queue.

A handler exception rolls back its transaction: variable changes are not saved, and the message or timer that was
being handled stays for the retry. Then the [error policy](error-handling.md) applies.

## Await event selection

One run of an await handles **one** event. Every event has a time:

- a message: the moment `sendMessage` stored it;
- `every`: its grid point;
- `after`: entering the await plus `delay`;
- `at`: the moment of the process timer.

An event is due when its time is not later than the engine clock. Among due events the one with the earliest time
is handled; on a tie the order is message, `every`, `after`, `at`, and messages go in arrival order. When the
dispatcher reached the process does not matter.

The handled event is consumed in the handler's transaction: the message is removed, `every` moves to its next grid
point after now, `after` is off until the process enters the await again, and an `at` timer the handler did not
move is cancelled. If the handler fails, all of this is rolled back with it, and the retry selects the same event.

### Retries pause the await

A handler error pauses the whole await until the retry time: new messages and due timers do not wake the process.
After the pause the earliest due event is handled, usually the failed one. So a temporary failure delays handling
but does not change the order of events.

The consequence: if a message accepted at 11:59 keeps failing, an `after` deadline at 12:00 waits for it; with
unlimited retries the deadline never fires. For a hard deadline, limit the retries
(`ProcessErrorHandler.fixed(maxRetries, delay)` with `onGiveUp`) or catch the error in the handler and return an
outcome that keeps the process waiting.

### Example

`after(1h)` from entering the await at 11:00, so the deadline is 12:00:

| Situation | Result |
|---|---|
| Message accepted at 11:59, handling started at 12:01 | the message is handled: it is earlier than the deadline |
| Message accepted at 11:59, its handling failed and is retried at 12:05 | the message is retried at 12:05, `after` waits for it |
| Message accepted at 11:59, handled with the await itself as the target | `after` fires right after it |
| Message accepted at 12:00:30, handling started at 12:01 | `after` fires; the message is removed with the queue |

So:

- `sendMessage` succeeding does not mean the handler runs: a message accepted after the deadline is removed on the
  deadline transition;
- a late `every` check sees the current state of the external system, which may have changed after the deadline;
- `every` points missed during downtime are not run in a batch: one check runs, then the grid continues;
- a process in `FAILED` handles no events; after `resumeFailedProcess` these rules apply again.

## Concurrent access

One node claims a process at a time. A transactional worker holds the process row lock while the handler runs, so
a concurrent `sendMessage` waits for the transition to commit and sees either the old await or the new state. A
non-transactional worker does not hold the lock: before saving the result, the engine checks that the process still
belongs to this node and has not changed; otherwise the result is discarded and the step runs again.

If a node has not renewed its registration for longer than `ruslo.dispatcher.instance-timeout`, other nodes take its
processes. The timeout is measured by database time, so node clock divergence does not affect it. The results of
steps that the lost node had started are discarded.

## Application transactions in Spring

Engine operations join the active application transaction (`PROPAGATION_REQUIRED`). So `newProcess(...).start()` and
`sendMessage()` inside `@Transactional` are atomic with the application changes on the same `DataSource` and
transaction manager: on rollback neither is saved. Process steps run after commit, each in its own transaction.

An engine error (a database error, `ProcessNotFoundException`, an undeclared message type) is thrown inside the
transaction and, as usual in Spring, marks it rollback-only even if caught.

An expected rejection is thrown after the engine leaves its transaction: `MessageNotExpectedException` from
`sendMessage` and `sendMessageSync`, `ProcessStatusException` from `resumeFailedProcess`. A caught rejection does not
prevent the application transaction from committing; an uncaught one rolls it back like any exception.

## Synchronous message handling

`processEngine.sendMessageSync(processId, type, payload)` runs the message handler in the calling thread and
transaction (or a new one). Use it when the caller needs the transition committed before the call returns, for
example for a user cancellation (the `cancellation` recipe).

- Only this message's handler runs synchronously; the dispatcher runs the following steps after commit.
- The message bypasses the queue: earlier messages and due timers keep waiting, and on a transition to another state
  the queued messages are removed.
- If the process does not expect the message now, the method throws `MessageNotExpectedException`; for a process in
  `FAILED` too, with `getStatus() == FAILED`. Nothing is stored.
- A handler exception propagates to the caller and rolls back the transaction: the message is not stored and the
  error policy is not called.
