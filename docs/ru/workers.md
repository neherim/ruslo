# Workers и маршрутизация

[English](../workers.md) | Русский

Worker — пул исполнения шагов со своим режимом и лимитом `max-concurrency`. Шаг использует worker процесса
(`defaultWorker(...)`, по умолчанию `DEFAULT`) или worker, переданный шагу сразу после состояния: `step(state, W, handler, ...)`.

- `DEFAULT` всегда транзакционный: на нём исполняются все обработчики сообщений и таймеров.
- Именованный worker может быть `non_transactional` — для шагов с долгими внешними вызовами.

Шаги, работающие с БД, оставляйте на `DEFAULT`. Долгий внешний I/O выносите в отдельный шаг на нетранзакционном
worker и делайте его идемпотентным. Чем отличаются режимы — в [guarantees.md](guarantees.md#режимы-worker).

## Пример: отдельный worker для I/O

Зарегистрируйте нетранзакционный worker в `application.yml`:

```yaml
ruslo:
  workers:
    io-worker:
      execution-mode: non_transactional
      max-concurrency: 50
```

Без Spring тот же worker регистрируется через `ProcessEngineBuilder`:

```java
new ProcessEngineBuilder(database)
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .build();
```

Назначьте его шагу с внешним вызовом; остальные шаги остаются на `DEFAULT`:

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

Медленным внешним системам давайте отдельные workers, чтобы они не занимали слоты друг друга.
`build()` проверяет, что все упомянутые workers зарегистрированы.
Полный пример: рецепт `externalcall` в [`ruslo-examples/cookbook`](../../ruslo-examples/cookbook).
