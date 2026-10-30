# Workers and routing

English | [Русский](ru/workers.md)

A worker is a step execution pool with its own mode and `max-concurrency` limit. A step uses the process worker
(`defaultWorker(...)`, `DEFAULT` by default) or the worker passed to the step right after its state: `step(state, W, handler, ...)`.

- `DEFAULT` is always transactional: all message and timer handlers run on it.
- A named worker can be `non_transactional`, for steps with long external calls.

Keep steps that work with the database on `DEFAULT`. Move long external I/O to a separate step on a
non-transactional worker and make it idempotent. How the modes differ: [guarantees.md](guarantees.md#worker-modes).

## Example: a separate worker for I/O

Register a non-transactional worker in `application.yml`:

```yaml
ruslo:
  workers:
    io-worker:
      execution-mode: non_transactional
      max-concurrency: 50
```

Without Spring, the same worker is registered through `ProcessEngineBuilder`:

```java
new ProcessEngineBuilder(database)
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .build();
```

Assign it to the step with the external call; the other steps stay on `DEFAULT`:

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

Give slow external systems separate workers, so that they do not take each other's slots.
`build()` checks that all referenced workers are registered.
Full example: the `externalcall` recipe in [`ruslo-examples/cookbook`](../ruslo-examples/cookbook).
