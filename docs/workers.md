# Workers and routing

English | [Русский](ru/workers.md)

A worker is a step execution pool with its own mode and `max-concurrency` limit. A step uses the process worker
(`defaultWorker(...)`, `DEFAULT` by default) or the worker set for the step: `step(..., options -> options.worker(W))`.

- `DEFAULT` is always transactional: all message and timer handlers run on it.
- A named worker can be `non_transactional`, for steps with long external calls.
- Terminal states do not use a worker.

How the modes differ in guarantees: [guarantees.md](guarantees.md#worker-modes). In short: keep await handlers and
steps that work with the database on `DEFAULT`; move long external I/O to a separate step on a non-transactional
worker and make it idempotent.

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
        .step(ExportState.EXPORTING, exportDocumentStep, ExportState.COMPLETED,
                options -> options.worker(IO_WORKER))
        .terminal(ExportState.COMPLETED)
        .build();
```

For slow external systems that must not take each other's slots, create separate workers with their own
`max-concurrency`. `build()` checks that all referenced workers are registered.
Full example: the `externalcall` recipe in [`ruslo-examples/cookbook`](../ruslo-examples/cookbook).
