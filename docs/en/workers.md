# Workers

A worker is a step execution pool with its own mode and `max-concurrency` limit. Every step runs on one worker: on the
process worker (`defaultWorker(...)`, `DEFAULT` by default) or on the worker passed to the step
(`step(state, W, handler, target)`, see [steps.md](steps.md#step-worker)).

Separate workers are needed to:

- run steps with long external calls outside a transaction, without holding a database connection;
- limit how many calls to one external service run at the same time;
- keep a slow system from taking the slots of steps that call other systems.

## The `DEFAULT` worker

The `DEFAULT` worker always exists and is always transactional: all message and timer handlers of awaits run on it.
It can be reconfigured (e.g. to increase `max-concurrency`), but not made non-transactional.

## Modes

| | `transactional` (default) | `non_transactional` |
|---|---|---|
| Application database changes made by the step | atomic with the transition: committed exactly once | not atomic: repeated when the step is retried |
| External calls (HTTP, brokers) | at least once | at least once |
| Database connection during the step | held | not held |
| Use for | short logic and database work | long external calls |

"Atomic" assumes the same `DataSource` and transaction manager.

A transactional worker holds the process row lock while the step runs. A non-transactional one does not: before
saving the result, the engine checks that the process has not changed; otherwise the result is discarded
([dispatcher.md](dispatcher.md#multiple-nodes)).

## Registration

In Spring Boot a worker is declared with the `ruslo.workers.<name>` properties, where `<name>` is the worker id:

```yaml
ruslo:
  workers:
    default:               # or DEFAULT
      max-concurrency: 20  # changes the limit of the built-in worker
    io-worker:             # creates a new worker
      execution-mode: non_transactional
      max-concurrency: 50
```

A worker can also be declared as a `WorkerSpec` bean. A worker from the properties replaces a bean with the same id.

Without Spring a worker is registered through `ProcessEngineBuilder`:

```java
new ProcessEngineBuilder(database)
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .build();
```

A worker can run on your own executor: `WorkerSpec.builder(id).customExecutor(executorService)`. The engine limits it
by `maxConcurrency`.

The definition's `build()` checks that all workers it refers to are registered. Property defaults are in
[configuration.md](configuration.md#settings).

## Capacity

`max-concurrency` is how many steps of the worker run at the same time **on one node**. Workers share the
application's connection pool, and a step of a transactional worker holds a connection while it runs. So size the
`max-concurrency` of transactional workers together with the pool size.

When all slots of a worker are taken, ready processes wait; the `ruslo.worker.lag` metric shows the delay
([metrics.md](metrics.md)). Give slow external systems separate workers, so that they do not take each other's slots.

## Example: a separate worker for I/O

Register the non-transactional worker `io-worker` as in [Registration](#registration) and assign it to the step with
the external call; the other steps stay on `DEFAULT`:

```java
public static final WorkerId IO_WORKER = new WorkerId("io-worker");

builder
        .type("DOCUMENT_EXPORT")
        .initialState(ExportState.QUEUED)
        .step(ExportState.QUEUED, validateDocumentStep, ExportState.EXPORTING)
        .step(ExportState.EXPORTING, IO_WORKER, exportDocumentStep, ExportState.COMPLETED)
        .terminal(ExportState.COMPLETED)
        .build();
```

A step on a non-transactional worker may run again, so make the external call idempotent.
