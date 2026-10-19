# Guarantees

English | [Русский](ru/guarantees.md)

A summary of what you can rely on when using the engine. Execution details are in [execution.md](execution.md),
errors in [error-handling.md](error-handling.md).

## Key points

- **Every transition is committed in its own transaction.** After commit, the process state and variables are saved;
  after a failure, only the handler whose transition was not committed runs again.
- **External calls run at least once, in any worker mode.** If the node fails or the transaction rolls back after
  an HTTP request or a Kafka send, the step runs again. Make external calls idempotent.
- **Steps of one process never run in parallel, and only one node saves the transition result.**
  Exception: if a node hangs longer than `instance-timeout` in the middle of a step on a non-transactional worker,
  another node takes the process, and the step may run twice for a while; the hung node's result is discarded.
- **A process is not lost when a node fails.** Another node takes it after about
  `ruslo.dispatcher.instance-timeout` (60 seconds by default).

## Worker modes

| | `transactional` (default, `DEFAULT`) | `non_transactional` |
|---|---|---|
| Application database changes made by the step (same `DataSource` and transaction manager in Spring) | atomic with the transition: committed exactly once | not atomic with the transition: run again when the step is retried |
| External calls (HTTP, brokers) | at least once | at least once |
| `ProcessContext` changes | atomic with the transition | saved with the transition; if the node lost the process (a pause longer than `instance-timeout`), discarded and the step is retried |
| Database connection during the step | held for the whole step | not held |
| Use for | short logic and database work | long external calls |

Message and timer handlers always run on the transactional `DEFAULT` worker.
How to assign another worker to a step: [workers.md](workers.md).

## Messages

- `sendMessage` accepts a message only if the process is now in an await that declares this type.
  Otherwise (the await has not started yet, has already ended, or the process has completed) the method throws
  `MessageNotExpectedException` with the current process state and stores nothing. **Early messages are not
  buffered**: the sender retries with a delay or uses a transactional outbox.
- The rejection is thrown after the engine's transaction, so a caught rejection does not affect the caller's
  transaction: for example, a step that reports to an already completed parent catches
  `MessageNotExpectedException` and goes on. An uncaught rejection is an ordinary error of the step or of the
  application transaction.
- A message type not declared in any await of the process (or declared with another payload class) is a caller
  error: `IllegalArgumentException`, and the transaction is marked rollback-only.
- A process in `FAILED` accepts messages of its await: they wait in the queue and are handled after `resumeFailedProcess`.
- A successful return from `sendMessage` means the message is stored, **not** that its handler will be called.
  If another await event (e.g. `timeout`) fires first and the process leaves the await, unhandled messages are
  removed.
- **`sendMessage` is not idempotent.** Sending the same payload again queues a second message.
  If the sender may repeat the call (e.g. after a timeout), the handler must detect duplicates,
  e.g. by an id in the payload. Example: the `webhook` recipe in [`ruslo-examples/cookbook`](../ruslo-examples/cookbook).
- A message handler runs in one transaction with removing the message from the queue: its database and
  `ProcessContext` changes are committed exactly once.
- Messages of one process are handled one at a time, in arrival order.
- A call inside an application `@Transactional` is atomic with its data: on rollback the message is not stored.
  The same applies to `newProcess(...).start()`.

## Time

- Timers (`timeout`, `every`, `retryAfter`) never fire before their scheduled time.
  Before pausing, the dispatcher reads the due time of the nearest process from the database and wakes up for it,
  so on an idle node a timer fires almost on time. A delay of up to `ruslo.dispatcher.max-interval`
  (10 seconds by default) is possible if the timer was scheduled on another node while this one was sleeping, and
  that node cannot take it itself. When worker capacity is short, the timer fires once capacity is free.
- The dispatcher of the node that saved a new process or message usually picks it up right after commit.
- Event time is measured by the engine `Clock` on each node. If node clocks diverge, the boundary
  "the message arrived before the deadline" is blurred by the divergence.

## What the engine does not do

- It does not store the definition version: a step code change affects running processes,
  see [definition evolution](concepts.md#definition-evolution).
- It does not store message history or payloads: a message is removed after it is handled.
- It does not delete completed processes, see [operations](operations.md#retention-of-completed-processes).
- It does not run steps of one process in parallel and does not support parallel branches.
