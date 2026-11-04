# Процессы

Эта страница описывает процесс целиком: как его объявить, запустить, какие данные он хранит и как живёт.

## Описание процесса

Описание процесса (`ProcessDefinition`) — это набор переходов между состояниями (`ProcessState`) трёх видов:

- **шаг** (`step`) выполняет код и всегда переходит дальше: [steps.md](steps.md);
- **ожидание** (`await`) ждёт сообщения или таймера: [awaits.md](awaits.md);
- **терминальное состояние** (`terminal`) завершает процесс.

В каждый момент процесс находится ровно в одном состоянии: параллельных ветвей внутри процесса нет. Параллельную
работу разбивайте на отдельные процессы.

Весь граф переходов виден в одном месте:

```java
public class ApprovalDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("APPROVAL");
    public static final MessageType<Decision> DECISION = MessageType.of("decision", Decision.class);

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
            .initialState(State.CREATED)
            .step(State.CREATED, ApprovalDefinition::notifyApprover, State.WAITING)
            .await(State.WAITING, await -> await
                .message(DECISION, ApprovalDefinition::onDecision, State.DONE)
                .after(Duration.ofDays(3), State.EXPIRED))
            .terminal(State.DONE)
            .terminal(State.EXPIRED)
            .build();
    }
}
```

Тип процесса (`ProcessType`) идентифицирует описание: по нему процессы запускают, ищут и тестируют. Начальное
состояние (`initialState`) — шаг или ожидание. Перед списком состояний описание задаёт настройки всего процесса:
worker по умолчанию (`defaultWorker`, см. [workers.md](workers.md)) и политику ошибок (`onError`, `onGiveUp`, см.
[error-handling.md](error-handling.md)).

Типы и состояния идентифицируются по имени. Движок возвращает объекты, объявленные в описании: в `ProcessContext`,
`ProcessInstanceDetails`, результатах поиска и исключениях. Поэтому `equals` и `==` с объявленными константами
работают: `details.state() == State.DONE`. Состояние, которого нет в текущем описании (см.
[Эволюция описания](#эволюция-описания)), и тип, не зарегистрированный в приложении, возвращаются как
`ProcessState.of(name)` и `ProcessType.of(name)`.

`build()` проверяет описание и бросает `IllegalArgumentException`, если оно несогласованно: например, для константы
результата не задан маршрут, шаг ссылается на незарегистрированный worker или переход ссылается на состояние не
тем объектом, которым оно объявлено.

В Spring Boot описание — это бин, который регистрируется при запуске: [spring.md](spring.md#definitions).

## Переходы

Цель перехода задаётся одним из двух способов:

- состоянием: `step(CREATED, handler, WAITING)`;
- маршрутами по результату обработчика: `routes -> routes.on(Outcome.OK, NEXT).on(Outcome.FAILED, CANCELLED)`.
  Обработчик возвращает константу enum, а описание сопоставляет каждой константе состояние. Так ветвятся и шаги, и
  события ожидания.

Обработчик сообщает результат, а описание решает, куда перейти. Поэтому весь граф виден в `define`.

Каждый вызов обработчика завершается одной транзакцией, которая сохраняет новое состояние и изменения
`ProcessContext`. Следующий обработчик выполняется в новой транзакции. Если следующее состояние исполняется на том же
worker, тот же узел сразу продолжает процесс; иначе процесс возвращается в общую очередь. Исключение обработчика
откатывает его транзакцию: состояние и переменные остаются прежними, а дальше действует
[политика ошибок](error-handling.md).

**Переход в то же состояние не считается сменой состояния.** Очередь сообщений, таймеры `every` и `after` и история
сохраняются, ошибка сбрасывается. Шаг выполняется снова, ожидание продолжает ждать. Например, так строятся циклы. Шаг, который
обрабатывает данные порциями. Ожидание, которое обрабатывает сообщения одно за другим.

## Запуск процесса

```java
UUID processId = processEngine.newProcess(OrderDefinition.TYPE)
        .businessKey("order-42")
        .variable(ORDER, order)
        .timer(PAYMENT_DUE, deadline)
        .start();
```

| Метод | Назначение |
|---|---|
| `businessKey(String)` | Ключ из предметной области, например номер заказа. По нему ищут процесс (`byBusinessKey`), когда приходит внешнее событие. Движок не проверяет его уникальность |
| `correlationId(String)` | Id для связи логов. Без него берётся текущий из MDC или создаётся новый |
| `variable(type, value)` | Начальная переменная процесса |
| `timer(timer, moment)` | Начальный таймер процесса для `at`, см. [awaits.md](awaits.md#таймеры-процесса-at) |

`start()` сохраняет процесс и возвращает его id.

## Контекст процесса

Обработчик получает `ProcessContext`:

| Метод | Назначение |
|---|---|
| `getVariable`, `setVariable`, `removeVariable` | Переменные процесса |
| `getTimer`, `setTimer`, `cancelTimer` | Таймеры процесса для `at`: [awaits.md](awaits.md#таймеры-процесса-at) |
| `processId()`, `processType()`, `state()`, `businessKey()`, `correlationId()` | Идентификация процесса |
| `attempt()` | Число неудачных попыток текущего шага или события: [error-handling.md](error-handling.md) |
| `now()` | Время движка; используйте вместо `Instant.now()`, чтобы им управляли тесты |

Изменения контекста сохраняются вместе с переходом процесса между состояниями.

Переменные и payload сообщений хранятся в JSON. Их класс должен быть конкретным: оберните `List` или `Map`
в record (`record OrderItems(List<String> itemIds) {}`).

## Статус

| Статус | Когда |
|---|---|
| `ACTIVE` | Процесс запущен и выполняется или ждёт |
| `COMPLETED` | Процесс перешёл в терминальное состояние |
| `FAILED` | Политика ошибок остановила процесс; он не выполняется, пока его не продолжат вручную |

Исключение в обработчике - техническая ошибка. Ожидаемые бизнес-ситуации выражайте результатами ветвящегося
обработчика. Как процесс попадает в `FAILED` и как его продолжить - в [error-handling.md](error-handling.md).

## Поиск процессов

`processEngine.processes()` возвращает запрос; фильтры объединяются через AND:

```java
List<ProcessInstanceSummary> failed = processEngine.processes()
        .byType(OrderDefinition.TYPE)
        .byStatus(ProcessStatus.FAILED)
        .list(100, 0);

Optional<ProcessInstanceDetails> order = processEngine.processes()
        .byBusinessKey("order-42")
        .singleDetails();
```

Фильтры: id, тип, состояние, статус, business key, correlation id, диапазоны `createdAt` и `nextRunAt`, процессы с
неудачными попытками (`byAttempted`) и шаблон `LIKE` по переменным. `list` возвращает summary, `single` и
`singleDetails` — не больше одного процесса и бросают `TooManyRowsException`, если подходят несколько.

`ProcessInstanceDetails` добавляет к summary переменные, таймеры процесса, последнюю ошибку и историю состояний.
Переменные читаются типизированно: `details.variables().get(ORDER_ID)` возвращает `Optional`.

Запрос выполняется в транзакции вызывающего. `withLock()` блокирует найденные строки через `SELECT ... FOR UPDATE` до
конца транзакции.

## Эволюция описания

Движок хранит имена типа и состояний, но не версию описания. Запущенные процессы используют описание текущего
приложения. Сохраняйте старые состояния, пока в них есть процессы. Если состояние всё же удалено, переведите затронутые процессы.
