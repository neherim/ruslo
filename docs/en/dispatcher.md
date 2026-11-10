# Dispatcher and nodes

The dispatcher is a background thread of the engine that finds processes ready to run and hands them to workers.
Copies of the application (nodes) do not talk to each other directly: they share the work through the process table
in the shared database.

## Process readiness: `next_run_at`

Every process stores in the `xs_process_instance.next_run_at` column the moment when it next has something to do. The
engine recomputes it on every change of the process, in the same transaction:

| Process | `next_run_at` |
|---|---|
| in a step | the moment of entering the step: run right away |
| in an await | the earliest of: the `every` point, the `after` deadline, the moment of the `at` timer, the receive time of the first `message` in the queue. Empty if the await waits only for messages and the queue is empty |
| waiting for a retry after an error | the retry time. New messages and timer changes do not move it ([await pause](awaits.md#errors-in-an-await)) |
| in `FAILED` or `COMPLETED` | empty |

A process is **ready** if it is `ACTIVE`, not claimed by a node, and its `next_run_at` is not later than the engine
`Clock` time. The dispatcher selects the ready processes of its workers (the `worker_id` column is the worker of the
current state) in `next_run_at` order, as many as the free slots allow.

## When a process is picked up

The dispatcher picks up ready processes as follows:

- after a process is started or a message is sent, the dispatcher of **the same node** wakes up right away;
- the dispatcher also wakes up at the time of the nearest timer or retry that this node knows about;
- successful polling cycles continue immediately. After an empty cycle, it waits for
  `ruslo.dispatcher.min-interval` (100 ms), doubling the delay after each empty cycle up to
  `ruslo.dispatcher.max-interval` (10 s). A successful cycle resets this backoff.

## Timer accuracy

Timers (`every`, `after`, `at`) and retries (`retryAfter`) never fire before their time. Usually they fire almost on
time. The delay can reach `max-interval` if another node scheduled the timer, or more if a queue of processes has
built up.

The delay does not change the order of await events: the event with the earliest time is handled, not the one the
dispatcher saw first ([awaits.md](awaits.md#which-event-is-handled)).

## Multiple nodes

One node claims a ready process: the process row is selected with `FOR UPDATE SKIP LOCKED`, and the node writes
itself into it as the owner. While the process is claimed, other nodes do not take it. So **steps of one process
never run in parallel, and only one node saves a transition**.

How a transition is protected depends on the worker mode ([workers.md](workers.md#modes)):

- a transactional worker holds the process row lock while the handler runs. A concurrent `sendMessage` waits for the
  transition to commit and sees either the old await or the new state;
- a non-transactional worker does not hold the lock. Before saving the result, the engine checks that the process
  still belongs to this node and has not changed; otherwise the result is discarded and the step runs again.

If the next state runs on the same worker, the node continues the process without releasing it; otherwise the
process returns to the shared queue and any node can take it.

## Node failure

Every node registers in the database and renews its registration every
`ruslo.dispatcher.instance-heartbeat-interval` (15 s). If a node has not renewed it for longer than
`ruslo.dispatcher.instance-timeout` (60 s), other nodes delete its registration and take its processes. So **a
process is not lost when a node fails**: it continues from the last committed transition.

If a node has not failed but hung longer than `instance-timeout` in the middle of a step on a non-transactional
worker, another node picks up the process, and the step may run twice. The hung node's result is discarded. This is
one more reason to make external calls idempotent ([steps.md](steps.md#repeated-execution)).

## Clocks

The engine uses two time sources:

- **database time** (`current_timestamp`) for node registration. So clock divergence between nodes does not affect
  `instance-timeout`;
- **the engine `Clock`** on each node for event time: the receive moment of a message, timer deadlines,
  `ProcessContext.now()`. Clock divergence between nodes blurs the boundary "the message arrived before the
  deadline": a message accepted by a node with a lagging clock may count as arrived before the deadline.

Synchronize node clocks (NTP). In tests the engine `Clock` is controlled by the emulator ([testing.md](testing.md)).
