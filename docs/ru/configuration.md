# Конфигурация

[English](../configuration.md) | Русский

Spring Boot starter не требует настроек `ruslo.*`: достаточно `DataSource`. Он создаёт таблицы движка,
транзакционный worker `DEFAULT` и запускает диспетчер. На этой странице — свойства на случай, когда значения по
умолчанию не подходят. Без Spring — см. [Конфигурация без Spring](#конфигурация-без-spring).

Неизвестные свойства `ruslo.*` ломают запуск, поэтому опечатка не игнорируется молча.

## Свойства

| Свойство | По умолчанию | Описание |
|---|---|---|
| `ruslo.database-migration.enabled` | `true` | Создавать и обновлять таблицы движка при запуске |
| `ruslo.history.enabled` | `true` | Хранить историю смены состояний в `xs_process_state_hist` |
| `ruslo.dispatcher.enabled` | `true` | Выполнять процессы в фоне на этом узле |
| `ruslo.dispatcher.min-interval` | `100ms` | Интервал опроса таблицы процессов, когда есть работа |
| `ruslo.dispatcher.max-interval` | `10s` | Интервал опроса, когда работы нет |
| `ruslo.dispatcher.instance-heartbeat-interval` | `15s` | Как часто узел подтверждает, что жив |
| `ruslo.dispatcher.instance-timeout` | `60s` | Через сколько без heartbeat процессы узла забирают другие узлы; не меньше четырёх интервалов heartbeat |
| `ruslo.workers.<name>.execution-mode` | `transactional` | `transactional` или `non_transactional` |
| `ruslo.workers.<name>.max-concurrency` | `10` | Сколько шагов worker одновременно выполняется на этом узле |
| `ruslo.logging.mdc.correlation-id-key` | `correlation_id` | Ключ MDC для correlation id |
| `ruslo.logging.mdc.process-id-key` | `process_id` | Ключ MDC для id процесса |
| `ruslo.logging.mdc.process-type-key` | `process_type` | Ключ MDC для типа процесса |

Свойства Admin UI (`ruslo.admin-ui.*`) описаны в его [README модуля](../../ruslo-admin-ui/README.md).

### Схема базы данных

Если схемой управляет Flyway, Liquibase или DBA, задайте `ruslo.database-migration.enabled=false` и примените
SQL-файлы из jar `ruslo-core`: `db/migration/<database>/V<version>__<name>.sql`.

### Workers

Ключ `<name>` — id worker. Worker `DEFAULT` существует всегда; объявите его под ключом `DEFAULT`, чтобы изменить его
`max-concurrency`. Он должен оставаться `transactional`, потому что на нём выполняются обработчики сообщений и
таймеров.

```yaml
ruslo:
  workers:
    DEFAULT:
      max-concurrency: 20
    io-worker:
      execution-mode: non_transactional
      max-concurrency: 50
```

Workers делят пул соединений с приложением: подбирайте `max-concurrency` с учётом размера пула. Назначение workers
шагам — в [workers.md](workers.md).

## Конфигурация без Spring

`ruslo-core` не зависит от Spring. Передайте ему `Database`: готовый `PlainJdbcDatabase` или свою реализацию.

```java
ProcessEngine engine = new ProcessEngineBuilder(new PlainJdbcDatabase(dataSource))
        .addProcessDefinition(new OrderProcessDefinition())
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .build();
engine.start();
```

`start()` выполняет миграцию и запускает диспетчер; до него операции движка бросают `IllegalStateException`.
Регистрируйте definitions до `start()`: процесс незарегистрированного типа не может выполняться. Если шагам нужен
сам движок, сначала постройте его, а definition зарегистрируйте через `engine.registerDefinition(...)`.

Рабочий пример — [`ruslo-examples/standalone-core`](../../ruslo-examples/standalone-core).

| Метод `ProcessEngineBuilder` | Spring-свойство |
|---|---|
| `withDatabaseMigrationEnabled(boolean)` | `ruslo.database-migration.enabled` |
| `withHistoryEnabled(boolean)` | `ruslo.history.enabled` |
| `withDispatcherEnabled(boolean)` | `ruslo.dispatcher.enabled` |
| `withMinProcessDispatchInterval(Duration)` | `ruslo.dispatcher.min-interval` |
| `withMaxProcessDispatchInterval(Duration)` | `ruslo.dispatcher.max-interval` |
| `withInstanceHeartbeatInterval(Duration)` | `ruslo.dispatcher.instance-heartbeat-interval` |
| `withInstanceTimeout(Duration)` | `ruslo.dispatcher.instance-timeout` |
| `registerWorker(WorkerSpec)` | `ruslo.workers.<name>.*` |
| `withCorrelationIdMdcKey(String)` | `ruslo.logging.mdc.correlation-id-key` |
| `withProcessIdMdcKey(String)` | `ruslo.logging.mdc.process-id-key` |
| `withProcessTypeMdcKey(String)` | `ruslo.logging.mdc.process-type-key` |
| `withJsonMapper(ObjectMapper)` | нет: свой `ObjectMapper` для переменных и сообщений |
| `withMetrics(ProcessEngineMetrics)` | нет: Micrometer подключается автоматически, см. [metrics.md](metrics.md) |

Worker может работать на вашем executor: `WorkerSpec.builder(id).customExecutor(executorService)`. Движок
ограничивает его `maxConcurrency`, но не останавливает.
