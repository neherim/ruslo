# Конфигурация

Движок настраивается через `ProcessEngineBuilder`. В Spring Boot его строит starter, а каждая настройка — свойство
`ruslo.*`: [spring.md](spring.md).

## Движок без Spring

`ruslo-core` не зависит от Spring, для минимальной настройки достаточно передать `Database` и определение процесса.
Для этого можно использовать готовый `PlainJdbcDatabase` или свою реализацию:

```java
ProcessEngine engine = new ProcessEngineBuilder(new PlainJdbcDatabase(dataSource))
        .addProcessDefinition(new OrderProcessDefinition())
        .build();
engine.start();
```

`engine.start()` выполняет миграцию схемы БД и запускает диспетчер запуска процессов.
Необходимо зарегистрировать все `ProcessDefinition` до `start()`, так как процесс незарегистрированного типа не может выполняться.
Если шагам процесса нужен `ProcessEngine`, то сначала постройте его, 
потом зарегистрируйте ваши `ProcessDefinition` через `engine.registerDefinition(...)` и запустите движок через `start()`

Рабочий пример — [`ruslo-examples/standalone-core`](https://github.com/neherim/ruslo/tree/main/ruslo-examples/standalone-core).

## Настройки

| Метод `ProcessEngineBuilder` | Spring-свойство | По умолчанию | Описание |
|---|---|---|---|
| `withDatabaseMigrationEnabled(boolean)` | `ruslo.database-migration.enabled` | `true` | Создавать и обновлять таблицы движка при запуске |
| `withHistoryEnabled(boolean)` | `ruslo.history.enabled` | `true` | Хранить историю смены состояний в `xs_process_state_hist` |
| `withDispatcherEnabled(boolean)` | `ruslo.dispatcher.enabled` | `true` | Выполнять процессы в фоне на этом узле |
| `withMinProcessDispatchInterval(Duration)` | `ruslo.dispatcher.min-interval` | `100ms` | Интервал опроса таблицы процессов, когда есть работа |
| `withMaxProcessDispatchInterval(Duration)` | `ruslo.dispatcher.max-interval` | `10s` | Интервал опроса, когда работы нет |
| `withInstanceHeartbeatInterval(Duration)` | `ruslo.dispatcher.instance-heartbeat-interval` | `15s` | Как часто узел подтверждает, что жив |
| `withInstanceTimeout(Duration)` | `ruslo.dispatcher.instance-timeout` | `60s` | Через сколько без heartbeat процессы узла забирают другие узлы; не меньше четырёх интервалов heartbeat |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.execution-mode` | `transactional` | `transactional` или `non_transactional` |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.max-concurrency` | `10` | Сколько шагов worker одновременно выполняется на этом узле |
| `withCorrelationIdMdcKey(String)` | `ruslo.logging.mdc.correlation-id-key` | `correlation_id` | Ключ MDC для correlation id |
| `withProcessIdMdcKey(String)` | `ruslo.logging.mdc.process-id-key` | `process_id` | Ключ MDC для id процесса |
| `withProcessTypeMdcKey(String)` | `ruslo.logging.mdc.process-type-key` | `process_type` | Ключ MDC для типа процесса |
| `withMetrics(ProcessEngineMetrics)` | нет | no-op | В Spring Micrometer подключается автоматически: [metrics.md](metrics.md) |

Свойства Admin UI (`ruslo.admin-ui.*`) описаны в его [README модуля](https://github.com/neherim/ruslo/blob/main/ruslo-admin-ui/README.md).

## Схема базы данных

Если схемой управляет Flyway, Liquibase или DBA, отключите миграцию и примените SQL-файлы из jar `ruslo-core`:
`db/migration/<database>/V<version>__<name>.sql`.

## Workers

Ключ `<name>` в `ruslo.workers.<name>.*` — id worker. Как объявить worker, перенастроить `DEFAULT` и подобрать
`max-concurrency` — в [workers.md](workers.md#регистрация).
