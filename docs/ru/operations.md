# Эксплуатация

[English](../operations.md) | Русский

## Перед production

- **База данных.** Для production используйте PostgreSQL; H2 поддерживается для тестов и локальной разработки.
- **Схема.** По умолчанию движок сам создаёт и обновляет таблицы `xs_*` при старте.
  Если схемой управляет Flyway, Liquibase или DBA, отключите `ruslo.database-migration.enabled`
  и примените SQL-файлы из jar `ruslo-core`: `db/migration/postgresql/V<версия>__<имя>.sql`.
- **Соединения.** Движок использует общий с приложением пул соединений к БД. Транзакционный шаг держит
  соединение всё время своего исполнения; учитывайте это при выборе размера пула и `max-concurrency` workers.
- **Мониторинг.** Главный сигнал проблем — метрика `ruslo.worker.lag`, см. [метрики](metrics.md).
- **Admin UI** не защищён сам по себе: закройте `/ruslo/**` правилами Spring Security,
  см. [README модуля](../../ruslo-admin-ui/README.md#spring-security).

## Поиск процессов

```java
List<ProcessInstanceSummary> failed = processEngine.processes()
        .byTypeName("ORDER")
        .byStatus(ProcessStatus.FAILED)
        .list(100, 0);

Optional<ProcessInstanceDetails> order = processEngine.processes().byBusinessKey("order-42").singleDetails();
```

`ProcessInstanceDetails` содержит все поля `ProcessInstanceSummary`, а также переменные, последнюю ошибку и историю состояний.
Переменные читаются типизированно: `details.variables().get(type)` возвращает `Optional` значения класса переменной,
`details.variables().asJsonMap()` — JSON-текст значений.
Формат последней ошибки описан в [error-handling.md](error-handling.md#гарантии).
Очередь сообщений через read API не видна.

## Ручное вмешательство

| Операция | Что делает |
|---|---|
| `processEngine.resumeFailedProcess(id)` | Возобновляет процесс из `FAILED`: упавший шаг или событие выполняется снова; `ProcessStatusException`, если процесс не в `FAILED` (активен или завершён) |
| `processEngineAdmin.moveProcessToState(id, state)` | Переводит процесс в любое объявленное состояние, в том числе из `FAILED`; очередь сообщений и ошибка сбрасываются, вход в terminal завершает процесс |
| `processEngineAdmin.setProcessVariable(id, type, value)` | Меняет переменную процесса |
| `processEngineAdmin.removeProcessVariable(id, name)` | Удаляет переменную процесса |
| `processEngineAdmin.countIncompleteProcessesByType(overdueAt)` | Число процессов в `ACTIVE` и `FAILED` по типам и число просроченных: в `ACTIVE` со `scheduledAt` не позже `overdueAt` |
| `processEngineAdmin.listApplicationInstances()` | Зарегистрированные инстансы движка, число захваченных ими процессов и срок регистрации |

`processEngineAdmin` — экземпляр `ProcessEngineAdmin`: в Spring Boot это готовый бин, без Spring он создаётся из движка
(`new ProcessEngineAdmin(processEngine)`). `ProcessEngine` административных операций не открывает.

Процесс в `FAILED` продолжает принимать сообщения своего ожидания (`sendMessage`): они обрабатываются после `resumeFailedProcess`.

Те же операции доступны в [Admin UI](admin-ui.md).

## Хранение завершённых процессов

Движок не удаляет завершённые процессы: строки `xs_process_instance` со статусом `COMPLETED` и их история
в `xs_process_state_hist` остаются в БД. Удаляйте их сами по расписанию; история удаляется каскадно:

```sql
delete from xs_process_instance
where status = 'COMPLETED' and update_at < now() - interval '90 days';
```

Если история состояний не нужна, её запись можно отключить свойством `ruslo.history.enabled=false`.
