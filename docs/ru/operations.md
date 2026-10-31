# Эксплуатация

## Перед production

- **База данных.** В production используйте PostgreSQL; H2 — для тестов и локального запуска.
- **Схема.** Движок создаёт и обновляет свои таблицы `xs_*` при запуске. Чтобы управлять схемой самостоятельно,
  см. [конфигурацию](configuration.md#схема-базы-данных).
- **Соединения.** Движок делит пул соединений с приложением, а транзакционный шаг держит соединение, пока
  выполняется. Подбирайте размер пула и `max-concurrency` workers вместе.
- **Мониторинг.** Следите за метрикой `ruslo.worker.lag` и числом процессов в `FAILED`, см. [метрики](metrics.md).
- **Admin UI.** Сам по себе он не защищён: закройте `/ruslo/**` через Spring Security, см.
  [README модуля](https://github.com/neherim/ruslo/blob/main/ruslo-admin-ui/README.md#spring-security).
- **Хранение.** Запланируйте очистку завершённых процессов, см. [ниже](#хранение-завершённых-процессов).

## Поиск процессов

```java
List<ProcessInstanceSummary> failed = processEngine.processes()
        .byTypeName("ORDER")
        .byStatus(ProcessStatus.FAILED)
        .list(100, 0);

Optional<ProcessInstanceDetails> order = processEngine.processes().byBusinessKey("order-42").singleDetails();
```

`ProcessInstanceDetails` добавляет к summary переменные, таймеры процесса, последнюю ошибку и историю состояний.
Переменные читаются типизированно: `details.variables().get(ORDER_ID)` возвращает `Optional`. `nextRunAt` — когда
движок в следующий раз возьмёт процесс; он пуст, если процесс ждёт только сообщений, находится в `FAILED` или
завершён.

## Ручное вмешательство

| Операция | Что делает |
|---|---|
| `processEngine.resumeFailedProcess(id)` | Продолжает процесс в `FAILED`: упавший шаг или событие выполняется снова |
| `processEngineAdmin.moveProcessToState(id, state)` | Переводит процесс в любое объявленное состояние, в том числе из `FAILED`, и сбрасывает ошибку |
| `processEngineAdmin.setProcessVariable(id, type, value)`, `removeProcessVariable(id, name)` | Меняет переменные процесса |
| `processEngineAdmin.setProcessTimer(id, timer, moment)`, `cancelProcessTimer(id, timer)` | Меняет таймер процесса, как `setTimer` и `cancelTimer` в обработчике; `Instant.now()` запускает `at` сразу |
| `processEngineAdmin.countIncompleteProcessesByType(overdueAt)` | Активные и упавшие процессы по типам и сколько из них просрочено |
| `processEngineAdmin.listApplicationInstances()` | Зарегистрированные узлы движка и захваченные ими процессы |

Перевод следует правилам переходов: перевод в другое состояние очищает очередь сообщений, перевод в текущее
состояние снова выполняет шаг или наступившее событие, перевод в terminal-состояние завершает процесс.

В Spring Boot `ProcessEngineAdmin` — бин; без Spring создайте его через `new ProcessEngineAdmin(processEngine)`.
Те же операции доступны в [Admin UI](admin-ui.md).

## Правка процесса в SQL

Предпочитайте `ProcessEngineAdmin`: он сохраняет согласованность строки процесса. Колонки `xs_process_instance` —
внутренние и могут меняться между версиями. Если во время инцидента всё же нужно изменить строку вручную:

- меняйте только строки с `lease_owner is null` и всегда увеличивайте `row_version`, иначе работающий worker может
  перезаписать изменение;
- после изменения задайте `next_run_at = current_timestamp`, чтобы движок взял процесс и пересчитал его таймеры.

```sql
update xs_process_instance
set next_run_at = current_timestamp, row_version = row_version + 1
where id = ? and status = 'ACTIVE' and lease_owner is null;
```

`update`, который не изменил ни одной строки, попал на захваченный процесс: повторите его.

## Хранение завершённых процессов

Движок не удаляет завершённые процессы. Удаляйте их по расписанию; история удаляется каскадно:

```sql
delete from xs_process_instance
where status = 'COMPLETED' and updated_at < now() - interval '90 days';
```

Если история состояний не нужна, отключите её через `ruslo.history.enabled=false`.
