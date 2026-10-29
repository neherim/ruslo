# Модель процесса

[English](../concepts.md) | Русский

Процесс — это набор явных состояний трёх видов:

- **шаг** (`step`) выполняет действие и всегда переходит дальше;
- **ожидание** (`await`) ждёт сообщения или таймера;
- **terminal** завершает процесс.

Начальное состояние — шаг или ожидание.

Главное правило: **обработчик сообщает результат, цель объявляет definition**. Обработчик не называет следующее
состояние: весь граф переходов, включая петли ожидания, виден в одном месте.

**Переход в то же состояние — не смена состояния.** Очередь сообщений, таймеры `every` и `after` и история
состояний не меняются; ошибка и `attempt` сбрасываются. Шаг выполняется снова; ожидание продолжает ждать своих
событий.

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
Шаг с целью в самого себя выполняется снова, без новой строки в истории состояний: цикл по пачкам, каждая пачка
фиксируется своим переходом (рецепт `batch`).

## Ожидания

Ожидание объявляет события:

| Событие | Когда срабатывает | Обработчик |
|---|---|---|
| `message(type, handler, next \| routes)` | пришло сообщение типа `type` | `ProcessMessageHandler<T>` или `BranchingMessageHandler<T, O>` |
| `every(interval, handler, next \| routes)` | повторно, каждые `interval` по сетке от входа в ожидание | `ProcessTimerHandler` или `BranchingTimerHandler<O>` |
| `after(delay[, handler], next \| routes)` | через `delay` после входа в ожидание | нет, `ProcessTimerHandler` или `BranchingTimerHandler<O>` |
| `at(timer[, handler], next \| routes)` | в момент таймера процесса `TimerType` | нет, `ProcessTimerHandler` или `BranchingTimerHandler<O>` |

Хотя бы одно событие должно вести в другое состояние.

```java
enum Payment { PAID, PENDING }

static final TimerType PAYMENT_DUE = TimerType.of("paymentDue");

.step(RESERVING, PaymentDefinition::reserve, WAITING_FOR_PAYMENT)               // ставит PAYMENT_DUE
.await(WAITING_FOR_PAYMENT, await -> await
    .every(Duration.ofSeconds(30), PaymentDefinition::checkPayment, routes -> routes   // polling внешнего условия
        .on(Payment.PAID, SHIPPING)
        .on(Payment.PENDING, WAITING_FOR_PAYMENT))                                 // ждать дальше
    .message(EXTEND_PAYMENT_DUE, PaymentDefinition::extend, WAITING_FOR_PAYMENT)   // ставит PAYMENT_DUE
    .after(Duration.ofDays(3), PaymentDefinition::remind, WAITING_FOR_PAYMENT)     // одно напоминание
    .at(PAYMENT_DUE, PaymentDefinition::markExpired, CANCELLED))

static void reserve(ProcessContext context) {
    context.setTimer(PAYMENT_DUE, context.now().plus(Duration.ofDays(5)));
}

static void extend(ProcessContext context, IncomingMessage<Instant> message) {
    context.setTimer(PAYMENT_DUE, message.payload());
}
```

Таймеры бывают двух видов. `every` и `after` — **таймеры ожидания**: они живут одно вхождение в ожидание, и их
моменты движок ставит от времени входа. `at` ждёт **таймер процесса**: именованный момент (`TimerType`), который
ставит и снимает код; он живёт между состояниями, пока не сработает или не будет снят.

Правила таймеров:

1. `every(interval)` срабатывает повторно, по сетке `вход в ожидание + initialDelay + k * interval`: опоздавшая
   проверка не сдвигает следующие, пропущенные точки не выполняются. `after(delay)` срабатывает через `delay` после
   входа в ожидание.
2. Таймер процесса ставит `context.setTimer(T, moment)` или при старте `newProcess(...).timer(T, moment)`, снимает
   `context.cancelTimer(T)`; `context.getTimer(T)` возвращает `Optional<Instant>`. Момент хранится с точностью до
   микросекунд, и `getTimer` возвращает усечённое значение. `T` должен использоваться в каком-нибудь `at` процесса,
   иначе эти методы и старт бросают `IllegalArgumentException`. `null` вместо момента — `NullPointerException`:
   снимает таймер только `cancelTimer`.
3. `at(T)` срабатывает, когда процесс в этом ожидании, а таймер `T` поставлен и наступил; момент в прошлом
   срабатывает сразу. Каждый вид таймера (`every`, `after`, `at`) объявляется в ожидании не больше одного раза;
   сочетать можно любые.
4. **Сработавший таймер с целью «само ожидание»** не выводит процесс из ожидания: `every` переходит к следующей
   точке сетки, `after` не сработает снова, пока процесс не придёт в ожидание из другого состояния. **Сработавший таймер процесса снимается до вызова обработчика**: обработчик видит
   `getTimer(T)` пустым и может поставить его снова, так получается расписание; обработчик, который его не поставил,
   оставляет ожидание ждать остальных событий. Момент срабатывания обработчик не получает: если он нужен как данные
   (дата отчёта, «оплачено до»), храните его в переменной.
5. Переходы между состояниями таймеры процесса не трогают: таймер, поставленный в шаге, ждёт ожидания, которое его
   объявило. Один таймер может объявляться в нескольких ожиданиях (сквозной дедлайн).
6. **Таймер процесса живёт, пока не сработал или не снят.** Ожидание, покинутое по сообщению, оставляет свой
   таймер поставленным: при новом входе в ожидание с этим `at` прошедший момент сработает сразу. Если таймер больше
   не нужен, обработчик, уводящий из ожидания, снимает его `cancelTimer`. То же при `moveProcessToState` оператором
   и при `ErrorResult.giveUp` из ожидания.
7. Ошибка обработчика приостанавливает всё ожидание до повтора: наступивший таймер ждёт, пока упавшее событие не
   будет обработано (см. [execution.md](execution.md#выбор-события-ожидания)).

Таймер без обработчика не может вести в само ожидание: он ничего бы не делал. Первую проверку `every` можно
выполнить сразу при входе в ожидание: `every(interval, handler, next, options -> options.initialDelay(Duration.ZERO))`.

Правило 6 на примере: ожидание с дедлайном, сообщение уводит в шаг, шаг возвращает в ожидание.

```java
.await(WAITING_FOR_DOCUMENTS, await -> await
    .message(DOCUMENT, Onboarding::onDocument, CHECKING)
    .at(DOCUMENTS_DUE, REJECTED))
.step(CHECKING, Onboarding::check, WAITING_FOR_DOCUMENTS)
```

Дедлайн общий для обоих входов в `WAITING_FOR_DOCUMENTS`: если он прошёл, пока выполнялся `CHECKING`, процесс
отклоняется сразу после возврата. Если каждому возврату нужен новый дедлайн, `check` ставит его заново.

Расписание по датам — это `at`, обработчик которого ставит таймер на следующую дату. Сама дата — данные, поэтому
хранится в переменной; таймер только будит процесс:

```java
static final VariableType<LocalDate> REPORT_DATE = VariableType.of("reportDate", LocalDate.class);
static final TimerType NEXT_RUN = TimerType.of("nextRun");

.step(PLAN, ReportDefinition::planFirstRun, SCHEDULED)          // пишет REPORT_DATE, ставит NEXT_RUN
.await(SCHEDULED, await -> await
    .at(NEXT_RUN, ReportDefinition::buildReport, routes -> routes
        .on(Run.MORE, SCHEDULED)                                // ставит NEXT_RUN на следующую REPORT_DATE
        .on(Run.LAST, DONE))
    .message(CANCEL, ReportDefinition::onCancel, CANCELLED))
```

`buildReport` строит отчёт за `REPORT_DATE`, записывает следующую дату и ставит `NEXT_RUN` на её момент
(`Run.MORE`) или возвращает `Run.LAST`. Дата и в переменной, и в таймере — плата за правило 4: сработавший таймер
снимается без исключений, поэтому обработчик никогда не видит устаревший момент и цикл не сработает дважды на одном
моменте.

**Событие с целью в само ожидание продолжает ждать**, как любой переход в то же состояние: обработанное
сообщение удаляется, остальные сообщения и таймеры сохраняются (расписание `every`, момент `after`,
таймеры процесса), строка в истории состояний не пишется. Так объявляются:

- периодический побочный эффект — `every(Duration.ofDays(1), sendReminder, WAITING_FOR_PAYMENT)`;
- промежуточные сообщения — `message(STATUS_UPDATE, saveStatus, WAITING_FOR_DELIVERY)`.

Любой переход в другое состояние закрывает ожидание и удаляет необработанные сообщения. Чтобы начать ожидание
заново — с пустой очередью и новым дедлайном `after`, — пройдите через шаг, ведущий обратно в ожидание. Например,
таймаут бездействия, который перезапускает каждое сообщение об активности:

```java
.await(ACTIVE, await -> await
    .message(ACTIVITY, Session::onActivity, RECORDING_ACTIVITY)
    .after(Duration.ofMinutes(30), EXPIRED))
.step(RECORDING_ACTIVITY, Session::recordActivity, ACTIVE)
```

Дедлайн, который должен сдвигаться, пока процесс остаётся в ожидании, — это `at`: поставьте его таймер.

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
| `getTimer(timer)`, `setTimer(timer, moment)`, `cancelTimer(timer)` | Таймеры процесса для `at`, см. [правила таймеров](#ожидания); изменения сохраняются вместе с переходом |
| `processId()`, `processType()`, `state()` | Идентификация процесса |
| `businessKey()` | Бизнес-ключ, заданный при запуске через `newProcess(...).businessKey(...)` |
| `correlationId()` | Идентификатор для логов: задаётся через `newProcess(...).correlationId(...)`, иначе берётся из MDC вызывающего потока или генерируется |
| `attempt()` | Число предыдущих неудачных попыток в текущем состоянии |
| `now()` | Текущее время часов движка, тех же, что у таймеров; используйте вместо `Instant.now()`, чтобы в тестах им управлял эмулятор |

Переменные и payload сообщений хранятся в БД как JSON (Jackson). Переменные процесса лежат в колонке
`xs_process_instance.variables` одним JSON-объектом, например `{"orderId":"A-1","amount":42}`; по нему
ищет `ProcessQuery.byVariablesLike("%\"orderId\":\"A-1\"%")`. Таймеры процесса хранятся отдельно, в колонке
`timers`. Меняйте их классы совместимо: уже запущенные
процессы прочитают сохранённый JSON новым классом. Собственный `ObjectMapper` задаётся через
`ProcessEngineBuilder.withJsonMapper(...)`.

`TimerType` принимает только непустое имя: `TimerType.of("paymentDue")`. `MessageType<T>` и `VariableType<T>`
принимают непустое имя и класс: `MessageType.of("payment", PaymentConfirmed.class)`
или `MessageType<PaymentConfirmed> payment = MessageType.of("payment")`. Класс должен быть конкретным, без параметров
типа: `List`, `Map` и другие generic-классы отклоняются с `IllegalArgumentException`. Оберните коллекцию в record:

```java
record OrderItems(List<String> itemIds) {}
VariableType<OrderItems> ITEMS = VariableType.of("items", OrderItems.class);
```

## Статус и ошибки

Статус процесса: `ACTIVE`, `FAILED` или `COMPLETED`. `COMPLETED` означает, что процесс дошёл до terminal.

Исключение обработчика — техническая ошибка: изменения откатываются, а дальше решает `ProcessErrorHandler`
(повторить, остановить в `FAILED` или уйти в состояние `onGiveUp`). Ожидаемые бизнес-ситуации выражайте outcome
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
- Обработчик не называет целевое состояние. Переход в то же состояние — не смена состояния: шаг выполняется
  снова, ожидание продолжает ждать с очередью и таймерами. Чтобы начать ожидание заново, пройдите через шаг.
- Чтобы добавить ветку, замените `ProcessStep` на `BranchingStep<Outcome>` и верните outcome: компилятор укажет
  место в definition, где цель нужно заменить на routes. Для событий ожидания аналогично:
  `ProcessMessageHandler<T>` → `BranchingMessageHandler<T, Outcome>`, `ProcessTimerHandler` → `BranchingTimerHandler<Outcome>`.
- Повтор после технической ошибки — `ErrorResult.retryAfter`, а не шаг, который повторяет сам себя.
- Периодическую проверку делайте через `every` в ожидании, а не циклом «шаг → ожидание → шаг»: приход в ожидание
  из шага очищает очередь и перезапускает `after`. Расписание по календарным датам — это `at`, обработчик которого
  ставит его таймер на следующую дату; саму дату храните в переменной.

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
