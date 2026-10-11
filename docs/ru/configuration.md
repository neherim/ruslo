# Конфигурация

[English](../configuration.md) | Русский

Свойства в этом файле относятся к Spring Boot автоконфигурации (`ruslo-spring-boot-starter`).
Большинство пользователей используют именно этот вариант. Если вы используете библиотеку без Spring —
см. раздел [«Конфигурация без Spring»](#конфигурация-без-spring) в конце файла.

---

## ruslo

| Свойство | Тип | По умолчанию | Описание |
|----------|-----|--------------|----------|
| `database-dialect` | `DatabaseDialect` | определяется автоматически | Явный SQL-диалект: `postgresql` или `h2` |

Если свойство не задано, `ProcessEngineBuilder.build()` один раз определяет диалект по JDBC metadata.
Явно заданный диалект позволяет не выполнять это определение.

---

## ruslo.history

| Свойство | Тип     | По умолчанию | Описание                                                                  |
|----------|---------|--------------|---------------------------------------------------------------------------|
| `enabled` | boolean | `true`      | Сохранять историю изменений состояний процессов в `xs_process_state_hist` |

---

## ruslo.database-migration

| Свойство  | Тип     | По умолчанию | Описание                                                  |
|-----------|---------|--------------|-----------------------------------------------------------|
| `enabled` | boolean | `true`       | Запускать встроенную миграцию схемы БД при старте движка |

Миграция создаёт таблицы `xs_schema_version`, `xs_process_instance`, `xs_process_state_hist`,
`xs_application_instance` и поддерживает PostgreSQL и H2. Если схемой управляет Flyway, Liquibase или DBA,
отключите свойство и примените SQL-файлы из jar `ruslo-core`: `db/migration/<database>/V<версия>__<имя>.sql`.

---

## ruslo.dispatcher

| Свойство          | Тип      | По умолчанию | Описание                                                                                             |
|-------------------|----------|--------------|------------------------------------------------------------------------------------------------------|
| `enabled`         | boolean  | `true`       | Запускать диспетчер и регистрацию узла при старте движка; если выключен, узел не исполняет процессы в фоне |
| `min-interval`    | Duration | `100ms`      | Минимальный интервал между опросами таблицы процессов (применяется, когда есть работа)               |
| `max-interval`    | Duration | `10s`        | Максимальный интервал между опросами таблицы процессов (применяется, когда работы нет)               |
| `instance-heartbeat-interval` | Duration | `15s` | Как часто узел подтверждает, что он жив                                                        |
| `instance-timeout` | Duration | `60s` | Сколько узел может не обновлять heartbeat, прежде чем его процессы передадут другим узлам |

`instance-timeout` определяет, через сколько после падения узла его процессы подхватят другие узлы.
Он должен быть не меньше четырёх `instance-heartbeat-interval`. Подробнее — в [execution.md](execution.md#конкурентный-доступ).

Таймеры от `max-interval` обычно не зависят: перед паузой диспетчер узнаёт из БД срок ближайшего процесса
и просыпается к нему. `max-interval` ограничивает опоздание, только если таймер назначен на другом узле,
пока этот узел спит, а сам тот узел не может его взять (например, у него нет такого worker'а).

---

## ruslo.workers.\<name\>

Секция описывает один или несколько воркеров, исполняющих шаги процессов на виртуальных потоках.
Ключ `<name>` становится идентификатором воркера (`WorkerId`).

> **Обязательное требование:** воркер с идентификатором `DEFAULT` должен быть объявлен явно или создаётся
> автоматически (транзакционный, `max-concurrency: 10`). Если нужно переопределить дефолтный
> worker через YAML, используйте ключ `DEFAULT`: он соответствует `WorkerId.DEFAULT`. Ключ `default`
> в нижнем регистре поддерживается как alias для `DEFAULT`, но `DEFAULT` и `default` нельзя задавать
> одновременно.
> `DEFAULT` обязан быть `transactional`: на нём исполняются все обработчики сообщений и таймеров.
> `non_transactional` доступен только именованным workers.

| Свойство          | Тип     | По умолчанию    | Описание |
|-------------------|---------|-----------------|----------|
| `execution-mode`  | Enum    | `transactional` | Режим исполнения шагов: `transactional` или `non_transactional` (регистр и разделитель не важны) |
| `max-concurrency` | Integer | `10`            | Максимальное количество одновременно выполняющихся шагов |

Гарантии режимов — в [guarantees.md](guarantees.md#режимы-worker), назначение воркеров шагам — в [workers.md](workers.md).

---

## ruslo.logging.mdc

Движок записывает в MDC только технические идентификаторы процесса: correlation id, id и тип процесса.
Текущее состояние процесса в MDC не записывается.

| Свойство             | Тип    | По умолчанию     | Описание                         |
|----------------------|--------|------------------|----------------------------------|
| `correlation-id-key` | String | `correlation_id` | Ключ MDC для correlation id      |
| `process-id-key`     | String | `process_id`     | Ключ MDC для id процесса         |
| `process-type-key`   | String | `process_type`   | Ключ MDC для типа процесса       |

---

## Минимальная конфигурация

Рабочий `application.yml` с настройкой источника данных и одним дефолтным воркером:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: myuser
    password: secret

ruslo:
  # Необязательно: без свойства диалект определяется по JDBC metadata.
  database-dialect: postgresql

  history:
    enabled: true

  workers:
    DEFAULT:
      execution-mode: transactional
      max-concurrency: 10
```

---

## Расширенная конфигурация

`application.yml` с двумя воркерами и явными настройками диспетчера:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: myuser
    password: secret

ruslo:
  history:
    enabled: true

  database-migration:
    enabled: true

  dispatcher:
    enabled: true
    min-interval: 50ms
    max-interval: 5s
    instance-heartbeat-interval: 15s
    instance-timeout: 60s

  workers:
    DEFAULT:
      execution-mode: transactional
      max-concurrency: 10
    io-worker:
      execution-mode: non_transactional
      max-concurrency: 50
```

Воркер `io-worker` предназначен для шагов с долгими IO-операциями (HTTP-запросы, вызовы внешних сервисов).
Назначение воркера шагам описывается в `ProcessDefinition` — подробнее в [workers.md](workers.md).

Движок использует общий с приложением `DataSource` и transaction manager. Ограничивайте одновременное
выполнение шагов через `max-concurrency` workers с учётом размера общего пула соединений и нагрузки приложения.

---

## Конфигурация без Spring

Библиотека `ruslo-core` не зависит от Spring и может использоваться в любом Java-приложении.
`Database` — это интерфейс из `ruslo-core`; Spring-стартер предоставляет готовую реализацию
`SpringJdbcDatabase` автоматически. Без Spring реализацию `Database` необходимо предоставить самостоятельно.

Рабочий standalone-пример с `PlainJdbcDatabase`, ручной регистрацией `WorkerSpec` и `ProcessDefinition` находится в
`ruslo-examples/standalone-core`:

```bash
./mvnw test -pl ruslo-examples/standalone-core -am
```

### Пример

```java
// Database — интерфейс из ruslo-core.
// Spring-стартер автоматически создаёт реализацию через SpringJdbcDatabase.
// Для standalone использования: реализуйте интерфейс Database самостоятельно
// или используйте PlainJdbcDatabase из ruslo-core.
Database database = new MyJdbcDatabase(dataSource);

ProcessEngine engine = new ProcessEngineBuilder(database)
        .addProcessDefinition(new OrderProcessDefinition())
        .registerWorker(WorkerSpec.builder(WorkerId.DEFAULT)
                .transactional()
                .maxConcurrency(10)
                .build())
        .registerWorker(WorkerSpec.builder(new WorkerId("io-worker"))
                .nonTransactional()
                .maxConcurrency(50)
                .build())
        .withDatabaseMigrationEnabled(true)
        .withDispatcherEnabled(true)
        .withDatabaseDialect(DatabaseDialect.H2)
        .withMinProcessDispatchInterval(Duration.ofMillis(100))
        .withMaxProcessDispatchInterval(Duration.ofSeconds(10))
        .withInstanceHeartbeatInterval(Duration.ofSeconds(15))
        .withInstanceTimeout(Duration.ofSeconds(60))
        .withHistoryEnabled(true)
        .withJsonMapper(myObjectMapper) // необязательно: ObjectMapper для переменных и сообщений
        .build();
engine.start();
```

`ProcessDefinition` можно передать builder'у до `build()` или зарегистрировать в уже собранном движке:

```java
ProcessEngine engine = new ProcessEngineBuilder(database).build();
// шаги процессов могут зависеть от объектов, которым нужен engine
engine.registerDefinition(new OrderProcessDefinition(stepsThatUse(engine)));
engine.start();
```

Регистрировать definitions рекомендуется до `start()`: диспетчер забирает готовые процессы любых типов,
и процесс незарегистрированного типа не сможет быть выполнен. Регистрация после `start()` допустима.
Повторная регистрация того же типа процесса или того же класса definition, а также ссылка на
незарегистрированный worker приводят к `IllegalArgumentException`; удалить или заменить definition нельзя.

`newProcess`, `sendMessage`, `sendMessageSync` и `resumeFailedProcess` доступны только после `start()`, до этого они
выбрасывают `IllegalStateException`.

### Соответствие методов Spring-свойствам

| Метод `ProcessEngineBuilder`              | Аналог Spring-свойства                          |
|-------------------------------------------|-------------------------------------------------|
| `withDatabaseMigrationEnabled(boolean)` | `ruslo.database-migration.enabled` |
| `withDatabaseDialect(DatabaseDialect)` | `ruslo.database-dialect` |
| `withDispatcherEnabled(boolean)` | `ruslo.dispatcher.enabled`          |
| `withMinProcessDispatchInterval(Duration)` | `ruslo.dispatcher.min-interval`        |
| `withMaxProcessDispatchInterval(Duration)` | `ruslo.dispatcher.max-interval`        |
| `withInstanceHeartbeatInterval(Duration)`  | `ruslo.dispatcher.instance-heartbeat-interval` |
| `withInstanceTimeout(Duration)`            | `ruslo.dispatcher.instance-timeout`    |
| `withHistoryEnabled(boolean)`             | `ruslo.history.enabled`                 |
| `withCorrelationIdMdcKey(String)`         | `ruslo.logging.mdc.correlation-id-key`  |
| `withProcessIdMdcKey(String)`             | `ruslo.logging.mdc.process-id-key`      |
| `withProcessTypeMdcKey(String)`           | `ruslo.logging.mdc.process-type-key`    |
| `registerWorker(WorkerSpec)`              | `ruslo.workers.<name>.*` (режим исполнения и лимиты worker) |
| `withJsonMapper(ObjectMapper)`            | нет                                             |

Пример создания `WorkerSpec`:

```java
WorkerSpec.builder(WorkerId.DEFAULT)
    .transactional()
    .maxConcurrency(10)
    .build()
```

Если необходим специальный platform-thread executor или интеграция с внешним планировщиком, используйте
`.customExecutor(executorService)`. Ruslo ограничит его через `maxConcurrency`, но не будет закрывать:
жизненным циклом внешнего executor'а управляет приложение. Внутренняя очередь такого executor'а также находится
в ответственности приложения; для строгой связи между `maxConcurrency` и фактически начатой работой выбирайте
bounded executor или executor с direct handoff.

Неизвестные Spring-свойства движка приводят к ошибке запуска, чтобы опечатка не применялась молча.
Секцию `ruslo.admin-ui.*` обрабатывает модуль админки при её включении.
