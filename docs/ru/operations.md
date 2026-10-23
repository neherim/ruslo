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

`nextRunAt` — когда движок в следующий раз возьмёт процесс: шаг, повтор, сообщение в очереди или таймер. Пусто,
если процесс ждёт только сообщений, находится в `FAILED` или завершён. Поиск по нему — `byNextRunAtFrom` /
`byNextRunAtTo`. Моментов отдельных таймеров в read API нет: момент `at` — значение его переменной, а выключенный
таймер виден по `nextRunAt`.

## Ручное вмешательство

| Операция | Что делает |
|---|---|
| `processEngine.resumeFailedProcess(id)` | Возобновляет процесс из `FAILED`: упавший шаг или событие выполняется снова; `ProcessStatusException`, если процесс не в `FAILED` (активен или завершён) |
| `processEngineAdmin.moveProcessToState(id, state)` | Переводит процесс в любое объявленное состояние, в том числе из `FAILED`; очередь сообщений и ошибка сбрасываются, вход в terminal завершает процесс |
| `processEngineAdmin.setProcessVariable(id, type, value)` | Меняет переменную процесса; запись переменной `at` текущего ожидания двигает его таймер; значение переменной `at` не типа `Instant` отклоняется в любом состоянии |
| `processEngineAdmin.removeProcessVariable(id, name)` | Удаляет переменную процесса; удаление переменной `at` текущего ожидания выключает его таймер |
| `processEngineAdmin.countIncompleteProcessesByType(overdueAt)` | Число процессов в `ACTIVE` и `FAILED` по типам и число просроченных: в `ACTIVE` с `nextRunAt` не позже `overdueAt`, в том числе с необработанным сообщением и наступившим таймером |
| `processEngineAdmin.listApplicationInstances()` | Зарегистрированные инстансы движка, число захваченных ими процессов и срок регистрации |

`processEngineAdmin` — экземпляр `ProcessEngineAdmin`: в Spring Boot это готовый бин, без Spring он создаётся из движка
(`new ProcessEngineAdmin(processEngine)`). `ProcessEngine` административных операций не открывает.

Процесс в `FAILED` продолжает принимать сообщения своего ожидания (`sendMessage`): они обрабатываются после `resumeFailedProcess`.

Те же операции доступны в [Admin UI](admin-ui.md).

## Правка таймеров в SQL

При разборе инцидента колонки таймеров `xs_process_instance` можно править напрямую:

| Колонка | Смысл |
|---|---|
| `next_run_at` | Когда движок в следующий раз возьмёт процесс: шаг, повтор, сообщение или таймер; `null`, если процесс ждёт только сообщений, находится в `FAILED` или завершён |
| `every_due_at` | Следующая точка сетки `every`; `null` у шага и у ожидания без `every` |
| `after_due_at` | Срок `after`; `null`, если он уже сработал или `after` не объявлен |
| `at_fired_due_at` | Момент `at` (значение переменной), уже обработанный в этом входе в ожидание; сбрасывается при входе в ожидание |

Два правила:

1. **`next_run_at` вычисляет движок** из колонок таймеров, очереди сообщений и повтора. Правка таймера без правки
   `next_run_at` подействует только в старое `next_run_at`. Изменив колонки таймеров, поставьте
   `next_run_at = current_timestamp`: движок возьмёт процесс, выберет наступившие события и сам пересчитает
   `next_run_at`. Процесс, взятый без наступившего события, только пересчитывает его: без обработчика, попытки
   и истории. Во время паузы ретрая (`attempt > 0`) `next_run_at` — время повтора: новые сообщения и перенос `at`
   его не сдвигают. «Запустить сейчас» в паузе сразу повторяет упавшее событие, число попыток сохраняется.
2. **Fencing.** Нетранзакционный worker сверяет `lease_owner` и `row_version` и может молча затереть правку.
   Правьте только строки с `lease_owner is null` и всегда с `row_version = row_version + 1`.

Рецепты (срок `at` меняется через `setProcessVariable`, а не в SQL: переменная `at`, записанная в SQL не как
`Instant`, — системная ошибка, и движок откладывает процесс на 60 секунд при каждом запуске, пока значение не
исправят):

```sql
-- Запустить сейчас
update xs_process_instance
set next_run_at = current_timestamp, row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;

-- Продлить срок after на сутки
update xs_process_instance
set after_due_at = after_due_at + interval '1 day', next_run_at = current_timestamp,
    row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;

-- Повторить срабатывание at для текущего значения переменной
update xs_process_instance
set at_fired_due_at = null, next_run_at = current_timestamp, row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;

-- Сдвинуть сетку every
update xs_process_instance
set every_due_at = timestamp with time zone '2026-10-21 12:00:00+00', next_run_at = current_timestamp,
    row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;
```

`update`, не изменивший ни одной строки, попал на захваченный процесс: повторите его.

## Хранение завершённых процессов

Движок не удаляет завершённые процессы: строки `xs_process_instance` со статусом `COMPLETED` и их история
в `xs_process_state_hist` остаются в БД. Удаляйте их сами по расписанию; история удаляется каскадно:

```sql
delete from xs_process_instance
where status = 'COMPLETED' and updated_at < now() - interval '90 days';
```

Если история состояний не нужна, её запись можно отключить свойством `ruslo.history.enabled=false`.
