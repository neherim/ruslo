# Workers

Worker — пул исполнения шагов со своим режимом и лимитом `max-concurrency`. Каждый шаг выполняется на одном worker:
на worker процесса (`defaultWorker(...)`, по умолчанию `DEFAULT`) или на worker, переданном шагу
(`step(state, W, handler, target)`, см. [steps.md](steps.md#worker-шага)).

Отдельные workers нужны, чтобы:

- выполнять шаги с долгими внешними вызовами вне транзакции и не держать соединение с БД;
- ограничить, сколько вызовов одного внешнего сервиса выполняется одновременно;
- не давать медленной системе занимать слоты шагов других систем.

## Worker `DEFAULT`

Worker `DEFAULT` существует всегда и всегда транзакционный: на нём выполняются все обработчики сообщений и таймеров
ожиданий. Его можно перенастроить (например, увеличить `max-concurrency`), но не сделать нетранзакционным.

## Режимы

| | `transactional` (по умолчанию) | `non_transactional` |
|---|---|---|
| Изменения в БД приложения из шага | атомарны с переходом: фиксируются ровно один раз | не атомарны: повторяются при повторе шага |
| Внешние вызовы (HTTP, брокеры) | хотя бы один раз | хотя бы один раз |
| Соединение с БД во время шага | занято | не занято |
| Для чего | короткая логика и работа с БД | долгие внешние вызовы |

«Атомарны» — при том же `DataSource` и transaction manager.

Транзакционный worker держит блокировку строки процесса, пока работает шаг. Нетранзакционный не держит: перед
сохранением результата движок проверяет, что процесс не изменился, иначе результат отбрасывается
([dispatcher.md](dispatcher.md#несколько-узлов)).

## Регистрация

В Spring Boot worker объявляется свойствами `ruslo.workers.<name>`, где `<name>` — id worker:

```yaml
ruslo:
  workers:
    default:               # или DEFAULT
      max-concurrency: 20  # меняет лимит встроенного worker
    io-worker:             # создает новый worker
      execution-mode: non_transactional
      max-concurrency: 50
```

Worker можно объявить и бином `WorkerSpec`. Worker из свойств заменяет бин с тем же id.

Без Spring worker регистрируется через `ProcessEngineBuilder`:

```java
new ProcessEngineBuilder(database)
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .build();
```

Worker может работать на вашем executor: `WorkerSpec.builder(id).customExecutor(executorService)`. Движок
ограничивает его `maxConcurrency`.

`build()` описания процесса проверяет, что все упомянутые в нём workers зарегистрированы. Значения по умолчанию
свойств — в [configuration.md](configuration.md#настройки).

## Ёмкость

`max-concurrency` — сколько шагов worker одновременно выполняется **на одном узле**. Workers используют общий пул
соединений приложения, а шаг транзакционного worker держит соединение, пока выполняется. Поэтому подбирайте
`max-concurrency` транзакционных workers вместе с размером пула.

Когда все слоты worker заняты, готовые процессы ждут; задержку показывает метрика `ruslo.worker.lag`
([metrics.md](metrics.md)). Медленным внешним системам давайте отдельные workers, чтобы они не занимали слоты друг
друга.

## Пример: отдельный worker для I/O

Зарегистрируйте нетранзакционный worker `io-worker`, как в разделе [Регистрация](#регистрация), и назначьте его
шагу с внешним вызовом; остальные шаги остаются на `DEFAULT`:

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

Шаг на нетранзакционном worker может выполниться повторно, поэтому делайте внешний вызов идемпотентным.
