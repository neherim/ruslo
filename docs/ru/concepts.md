# Модель процесса

[English](../concepts.md) | Русский

Процесс — это набор явных состояний трёх видов:

- **шаг** (`step`) выполняет действие и всегда переходит дальше;
- **ожидание** (`await`) ждёт сообщения или таймера;
- **terminal** завершает процесс.

Начальное состояние — шаг или ожидание.

Главное правило: **обработчик сообщает результат, цель объявляет definition**. Обработчик не называет следующее
состояние: весь граф переходов, включая петли ожидания, виден в одном месте.

```java
MessageType<String> APPROVED = MessageType.of("approved", String.class);

ProcessDefinition definition = builder -> builder.type("APPROVAL")
    .initialState(State.CREATE)
    .step(State.CREATE, context -> createOrder(context), State.WAIT)
    .await(State.WAIT, await -> await
        .message(APPROVED, (context, message) -> { }, State.DONE)
        .after(Duration.ofHours(1), State.EXPIRED))
    .terminal(State.DONE)
    .terminal(State.EXPIRED)
    .build();
```

Процесс идентифицируется только типом (`ProcessType`), он же используется для запуска:
`processEngine.newProcess(type).start()`. Тип удобно объявлять константой в классе definition — тогда
запуск, поиск и тесты ссылаются на одно и то же значение, а один класс definition можно зарегистрировать
под разными типами:

```java
public class ApprovalProcessDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("APPROVAL");

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
            ...
    }
}

UUID processId = processEngine.newProcess(ApprovalProcessDefinition.TYPE).start();
```

## Шаги

Линейный шаг реализует `ProcessStep` (`void execute(context)`); его цель — третий аргумент `step`.

Ветвящийся шаг реализует `BranchingStep<O>` и возвращает константу enum `O`, а definition сопоставляет каждую
константу с состоянием:

```java
enum Validation { VALID, REJECTED }

BranchingStep<Validation> validateStep = context -> isValid(context) ? Validation.VALID : Validation.REJECTED;

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

Компилятор проверяет, что enum шага и routes совпадают, а `build()` — что покрыты все константы.
Шаг с целью в самого себя выполняется снова.

## Ожидания

Ожидание объявляет события:

| Событие | Когда срабатывает | Обработчик |
|---|---|---|
| `message(type, handler, next \| routes)` | пришло сообщение типа `type` | `ProcessMessageHandler<T>` или `BranchingMessageHandler<T, O>` |
| `every(interval, handler, next \| routes)` | повторно, каждые `interval` по сетке от входа в ожидание | `ProcessTimerHandler` или `BranchingTimerHandler<O>` |
| `after(delay[, handler], next \| routes)` | через `delay` после входа в ожидание | нет, `ProcessTimerHandler` или `BranchingTimerHandler<O>` |
| `at(variable[, handler], next \| routes)` | в момент из переменной типа `Instant` | нет, `ProcessTimerHandler` или `BranchingTimerHandler<O>` |

Хотя бы одно событие должно вести в другое состояние.

```java
enum Payment { PAID, PENDING }

static final VariableType<Instant> DUE_DATE = VariableType.of("dueDate", Instant.class);

.await(WAITING_FOR_PAYMENT, await -> await
    .every(Duration.ofSeconds(30), checkPayment, routes -> routes   // polling внешнего условия
        .on(Payment.PAID, SHIPPING)
        .on(Payment.PENDING, WAITING_FOR_PAYMENT))                 // ждать дальше
    .message(EXTEND_DUE_DATE, extendDueDate, WAITING_FOR_PAYMENT)  // пишет DUE_DATE
    .after(Duration.ofDays(3), remind, WAITING_FOR_PAYMENT)         // одно напоминание
    .at(DUE_DATE, markExpired, CANCELLED))
```

Правила таймеров:

1. `every(interval)` срабатывает повторно, по сетке `вход в ожидание + initialDelay + k * interval`: опоздавшая
   проверка не сдвигает следующие, пропущенные точки не выполняются. `after(delay)` срабатывает через `delay` после
   входа в ожидание. `at(variable)` срабатывает в момент из переменной: нет переменной — нет таймера; переменная
   изменилась — таймер передвинулся. Переменная всегда `Instant`, в любом состоянии и при старте: другое значение
   отклоняется при записи.
2. Каждый таймер объявляется в ожидании не больше одного раза; сочетать можно любые.
3. **Таймер срабатывает один раз на свой момент.** Момент `after` в пределах входа не меняется, поэтому он
   срабатывает один раз за вход. После срабатывания `at` ждёт нового значения переменной: обработчик записал
   следующую дату — получилось расписание; не записал — ожидание ждёт остальных событий. Процесс при этом не
   падает: это то же, что ожидание, сообщение для которого не приходит.
4. Ошибка обработчика приостанавливает всё ожидание до повтора: наступивший таймер ждёт, пока упавшее событие не
   будет обработано (см. [execution.md](execution.md#выбор-события-ожидания)).

Таймер без обработчика не может вести в само ожидание: он ничего бы не делал. Первую проверку `every` можно
выполнить сразу при входе в ожидание: `every(interval, handler, next, options -> options.initialDelay(Duration.ZERO))`.

Момент для `at` вычисляет шаг или обработчик от `context.now()`, например
`context.setVariable(DUE_DATE, context.now().plus(Duration.ofMinutes(15)))`, либо передаёт вызывающий код при
запуске. Расписание по датам — это `at`, обработчик которого пишет следующую дату; первую дату пишет вызывающий
код при запуске или шаг перед ожиданием:

```java
.step(PLAN, ReportDefinition::planFirstRun, SCHEDULED)          // пишет NEXT_RUN
.await(SCHEDULED, await -> await
    .at(NEXT_RUN, ReportDefinition::buildReport, routes -> routes
        .on(Run.MORE, SCHEDULED)                                // ждать следующей даты
        .on(Run.LAST, DONE))
    .message(CANCEL, ReportDefinition::onCancel, CANCELLED))
```

**Цель события, равная самому ожиданию, означает «ждать дальше».** Это не повторный вход в состояние:
обработанное сообщение удаляется, остальные сообщения и таймеры сохраняются (расписание `every`, моменты `after`
и `at`), строка в истории состояний не пишется. Так объявляются:

- периодический побочный эффект — `every(Duration.ofDays(1), sendReminder, WAITING_FOR_PAYMENT)`;
- промежуточные сообщения — `message(STATUS_UPDATE, saveStatus, WAITING_FOR_DELIVERY)`.

Любой переход в другое состояние закрывает ожидание и удаляет необработанные сообщения. Чтобы начать ожидание
заново — с пустой очередью и новым дедлайном `after`, — уйдите в шаг, ведущий обратно в ожидание. Дедлайн, который
должен сдвигаться без повторного входа, — это `at`: запишите новый момент в его переменную.

Если наступило несколько событий, обрабатывается самое раннее; точные правила — в [execution.md](execution.md#выбор-события-ожидания).

## Сообщения

Первый аргумент `message` объявляет имя и класс payload. Обработчик получает `IncomingMessage<T>` с id, типом,
payload и временем приёма.

Сообщение принимается, только когда процесс находится в ожидании, которое его объявило. Ранние и запоздавшие
сообщения не сохраняются, `sendMessage` бросает `MessageNotExpectedException`; буферизации нет. Учтите, что каждый шаг перед ожиданием
фиксируется отдельно, поэтому между запуском процесса и входом в ожидание есть окно, когда сообщение будет
отклонено. Гарантии доставки описаны в [guarantees.md](guarantees.md#сообщения).

## Контекст процесса

Обработчик получает `ProcessContext`:

| Метод | Назначение |
|---|---|
| `getVariable(type)`, `setVariable(type, value)`, `removeVariable(type)` | Переменные процесса; изменения сохраняются вместе с переходом |
| `processId()`, `processType()`, `state()` | Идентификация процесса |
| `businessKey()` | Бизнес-ключ, заданный при запуске через `newProcess(...).businessKey(...)` |
| `correlationId()` | Идентификатор для логов: задаётся через `newProcess(...).correlationId(...)`, иначе берётся из MDC вызывающего потока или генерируется |
| `attempt()` | Число предыдущих неудачных попыток в текущем состоянии |
| `now()` | Текущее время часов движка, тех же, что у таймеров; используйте вместо `Instant.now()`, чтобы в тестах им управлял эмулятор |

Переменные и payload сообщений хранятся в БД как JSON (Jackson). Переменные процесса лежат в колонке
`xs_process_instance.variables` одним JSON-объектом, например `{"orderId":"A-1","amount":42}`; по нему
ищет `ProcessQuery.byVariablesLike("%\"orderId\":\"A-1\"%")`. Меняйте их классы совместимо: уже запущенные
процессы прочитают сохранённый JSON новым классом. Собственный `ObjectMapper` задаётся через
`ProcessEngineBuilder.withJsonMapper(...)`.

`MessageType<T>` и `VariableType<T>` принимают непустое имя и класс: `MessageType.of("payment", PaymentConfirmed.class)`
или `MessageType<PaymentConfirmed> payment = MessageType.of("payment")`. Класс должен быть конкретным, без параметров
типа: `List`, `Map` и другие generic-классы отклоняются с `IllegalArgumentException`. Оберните коллекцию в record:

```java
record OrderItems(List<String> itemIds) {}
VariableType<OrderItems> ITEMS = VariableType.of("items", OrderItems.class);
```

## Статус и ошибки

Статус процесса: `ACTIVE`, `FAILED` или `COMPLETED`. `COMPLETED` означает, что процесс дошёл до terminal.

Исключение обработчика — техническая ошибка: изменения откатываются, а дальше решает `ProcessErrorHandler`
(повторить, остановить в `FAILED` или уйти в запасное состояние). Ожидаемые бизнес-ситуации выражайте outcome
ветвящегося обработчика. Подробнее — в [error-handling.md](error-handling.md).

## Описание состояния

`ProcessState.description()` задаёт человекочитаемую подпись, которую Admin UI показывает на схеме процесса.
По умолчанию она совпадает с `name()`. Описание не может быть пустым и не влияет ни на идентичность состояния,
ни на исполнение:

```java
enum OrderState implements ProcessState {
    CREATED("Создание заказа"),
    WAITING_FOR_PAYMENT("Ожидание оплаты");

    private final String description;

    OrderState(String description) { this.description = description; }

    @Override
    public String description() { return description; }
}
```

## Жизненный цикл обработчиков

Definition строится один раз при регистрации в engine, а его шаги и обработчики вызываются конкурентно многими
процессами. Делайте их stateless или thread-safe; данные конкретного процесса храните в `ProcessContext`,
а не в полях обработчика.

В Spring обработчики передаются в definition через constructor injection или через `bean(Type.class)` базового
класса `SpringProcessDefinition`. Без Spring экземпляры создаются явно до регистрации definition в `ProcessEngineBuilder`.

## Ошибки компиляции

Тип обработчика (`ProcessStep` / `BranchingStep<O>`, `ProcessMessageHandler<T>` / `BranchingMessageHandler<T, O>`,
`ProcessTimerHandler` / `BranchingTimerHandler<O>`) связан с формой объявления в definition, поэтому большинство
ошибок видно на компиляции. Текст сообщений зависит от версии `javac` и IDE; ориентируйтесь на признак.

| Ошибка | Признак | Исправление |
|---|---|---|
| Лямбда линейного шага возвращает outcome | `no suitable method found for step(...)`: `bad return type in lambda expression` | Объявить routes (шаг становится ветвящимся) или ничего не возвращать |
| Ветвящийся шаг объявлен с одной целью | `no suitable method found for step(...)`: `ValidateStep cannot be converted to ProcessStep` | Заменить цель на `routes -> routes.on(...)` |
| Линейный шаг объявлен с routes | `no suitable method found for step(...)`: `CreateStep cannot be converted to BranchingStep<O>` | Заменить routes на целевое состояние |
| В routes outcome чужого enum | `Stock cannot be converted to Validation` | Использовать константы того же enum, что возвращает шаг |
| Обработчик события ожидания | те же признаки с `message(...)` / `every(...)` | Как у шагов: routes для outcome, целевое состояние для линейного обработчика |

Остальную структуру проверяет `build()`: повторяющиеся состояния и события, объявленность целей, полноту routes,
положительность задержек и наличие выхода из каждого ожидания.

## Правила для пользователей и ИИ-агентов

- Не используйте raw types и приведения типов у обработчиков: они обходят проверки компилятора, и ошибка формы
  обработчика обнаружится только в runtime.
- Обработчик не называет целевое состояние. Шаг всегда уходит по definition; остаться можно только в ожидании —
  событием с целью в само ожидание.
- Чтобы добавить ветку, замените `ProcessStep` на `BranchingStep<Outcome>` и верните outcome: компилятор укажет
  место в definition, где цель нужно заменить на routes. Для событий ожидания аналогично:
  `ProcessMessageHandler<T>` → `BranchingMessageHandler<T, Outcome>`, `ProcessTimerHandler` → `BranchingTimerHandler<Outcome>`.
- Повтор после технической ошибки — `ErrorResult.retryAfter`, а не шаг, который повторяет сам себя.
- Периодическую проверку делайте через `every` в ожидании, а не циклом «шаг → ожидание → шаг»: повторный вход
  в ожидание очищает очередь и сбрасывает `after`. Расписание по календарным датам — это `at`, обработчик которого
  пишет следующую дату.

## Эволюция definition

Движок сохраняет имена типа и состояния, но не версию definition. Уже запущенные процессы используют definition,
зарегистрированное в текущем приложении:

- изменение обработчика влияет и на живые процессы;
- удаление или переименование состояния (или типа) оставляет живые процессы в старом состоянии, которое движок
  не может исполнить. Такой процесс откладывается на 60 секунд с ошибкой в `lastError`, пока его не исправят.

Проще всего сохранять старые состояния, пока в них есть живые процессы. Если состояние всё же удалено, найдите
затронутые процессы и переведите их в зарегистрированное состояние:

```java
processEngine.processes().byTypeName("ORDER").byStateName("OLD_STATE").list()
        .forEach(p -> processEngineAdmin.moveProcessToState(p.id(), OrderState.NEW_STATE));
```

Переход очищает очередь сообщений; переменные должны подходить новому обработчику. Для удалённого типа сначала
верните его definition: `moveProcessToState` не меняет тип.
