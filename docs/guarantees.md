# Guarantees

English | [Русский](ru/guarantees.md)

What you can rely on when using the engine. The exact rules are in [execution.md](execution.md).

## Key points

- **Every transition is committed in its own transaction.** After a failure, only the handler whose transition was
  not committed runs again.
- **External calls run at least once, in any worker mode.** If the node fails or the transaction rolls back after an
  HTTP request or a Kafka send, the step runs again. Make external calls idempotent.
- **Steps of one process never run in parallel, and only one node saves a transition.** Exception: if a node hangs
  longer than `instance-timeout` in the middle of a step on a non-transactional worker, another node takes the
  process and the step may run twice; the hung node's result is discarded.
- **A process is not lost when a node fails.** Another node takes it after `ruslo.dispatcher.instance-timeout`
  (60 seconds by default).

## Worker modes

| | `transactional` (default) | `non_transactional` |
|---|---|---|
| Application database changes made by the step | atomic with the transition: committed exactly once | not atomic: repeated when the step is retried |
| External calls (HTTP, brokers) | at least once | at least once |
| Database connection during the step | held | not held |
| Use for | short logic and database work | long external calls |

"Atomic" assumes the same `DataSource` and transaction manager. Message and timer handlers always run on the
transactional `DEFAULT` worker. Assigning workers: [workers.md](workers.md).

## Messages

- `sendMessage` accepts a message only if the process is now in an await that declares its type. Otherwise (the
  await has not started yet, has ended, or the process has completed) it throws `MessageNotExpectedException` with
  the current process state and stores nothing. **Early messages are not buffered**: the sender retries later or
  ignores the rejection. Catching it does not roll back the caller's transaction
  ([spring.md](spring.md#application-transactions)).
- A message type that no await of the process declares is a caller error: `IllegalArgumentException`.
- A process in `FAILED` accepts messages of its await; they are handled after `resumeFailedProcess`.
- Success means the message is stored, **not** that its handler will run: if another event moves the process out of
  the await first, unhandled messages are removed.
- **`sendMessage` is not idempotent**: sending the same payload again queues a second message. If the sender may
  repeat the call, the handler detects duplicates, e.g. by an id in the payload (the `webhook` recipe).
- A message handler runs in one transaction with removing the message from the queue, and messages of one process are
  handled one at a time, in arrival order.
- Inside an application `@Transactional`, `sendMessage` and `newProcess(...).start()` are atomic with its data.

## Time

- Timers (`every`, `after`, `at`, `retryAfter`) never fire before their time. Usually they fire almost on time; the
  delay can reach `ruslo.dispatcher.max-interval` (10 seconds by default) when another node scheduled the timer, and
  longer when worker capacity is short.
- A new process or message is usually picked up right after commit.
- Event time is measured by the engine `Clock` of each node: clock divergence between nodes blurs the boundary
  "the message arrived before the deadline".

## What the engine does not do

- It does not store definition versions: a code change affects running processes, see
  [definition evolution](concepts.md#definition-evolution).
- It does not keep message history: a message is removed after it is handled.
- It does not delete completed processes, see [operations](operations.md#retention-of-completed-processes).
- It does not support parallel branches inside one process: fan out into separate processes (the `fanout` recipe).
