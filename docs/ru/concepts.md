# Модель процесса

[English](../concepts.md) | Русский

Процесс — это набор явных состояний трёх видов:

- **шаг** (`step`) выполняет код и всегда переходит дальше;
- **ожидание** (`await`) ждёт сообщения или таймера;
- **terminal** завершает процесс.

Главное правило: **обработчик сообщает результат, цель объявляет definition.** Обработчик не называет следующее
состояние, поэтому весь граф переходов виден в одном месте.

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

UUID processId = processEngine.newProcess(ApprovalDefinition.TYPE).start();
```

Тип процесса (`ProcessType`) идентифицирует definition: по нему процессы запускают, ищут и тестируют. Объявите его,
типы сообщений и типы переменных константами и ссылайтесь на них везде.

## Шаги

Линейный шаг реализует `ProcessStep` (`void execute(context)`); его цель — третий аргумент `step`.

Ветвящийся шаг реализует `BranchingStep<O>` и возвращает константу enum `O`, а definition сопоставляет каждую
константу с состоянием:

```java
enum Validation { VALID, REJECTED }

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

Компилятор проверяет, что маршруты используют enum шага, а `build()` — что для каждой константы задан маршрут.

Шаг, целью которого указан он сам, выполняется снова: цикл по пачкам, где каждая пачка фиксируется своим переходом
(рецепт `batch`).

## Ожидания

Ожидание объявляет события, которых ждёт:

| Событие | Срабатывает | Обработчик |
|---|---|---|
| `message(type, handler, target)` | когда приходит сообщение типа `type` | обязателен |
| `every(interval, handler, target)` | регулярно, каждые `interval` | обязателен |
| `after(delay[, handler], target)` | один раз, через `delay` после входа в ожидание | необязателен |
| `at(timer[, handler], target)` | в момент таймера процесса `timer` | необязателен |

`target` — состояние или, для ветвящегося обработчика, `routes -> routes.on(...)`. Каждый вид таймера объявляется в
ожидании не больше одного раза, и хотя бы одно событие ведёт в другое состояние.

```java
.await(WAITING_FOR_PAYMENT, await -> await
    .message(PAYMENT_CONFIRMED, PaymentDefinition::onPaid, SHIPPING)
    .every(Duration.ofDays(1), PaymentDefinition::remind, WAITING_FOR_PAYMENT)   // ежедневное напоминание
    .at(PAYMENT_DUE, CANCELLED))                                               // срок, который задаёт код
```

### Остаться в ожидании

Событие, целью которого указано само ожидание, оставляет процесс ждать: обработанное сообщение удаляется, остальные
сообщения в очереди и таймеры сохраняются. Так делают напоминания, опрос внешней системы и промежуточные сообщения
(`message(STATUS_UPDATE, saveStatus, WAITING_FOR_DELIVERY)`).

Переход в другое состояние закрывает ожидание, и **его необработанные сообщения удаляются**. Чтобы начать ожидание
заново, с пустой очередью и новыми таймерами, пройдите через шаг, который ведёт обратно в него:

```java
.await(ACTIVE, await -> await
    .message(ACTIVITY, Session::onActivity, RECORDING_ACTIVITY)   // каждая активность перезапускает таймаут
    .after(Duration.ofMinutes(30), EXPIRED))
.step(RECORDING_ACTIVITY, Session::recordActivity, ACTIVE)
```

То же правило действует для шагов и ручных переходов: переход в текущее состояние — не смена состояния.

### Таймеры ожидания: `every` и `after`

`every` и `after` относятся к одному входу в ожидание; движок задаёт их, когда процесс входит в него.

- `after(delay)` срабатывает один раз, через `delay` после входа.
- `every(period)` срабатывает по фиксированной сетке от входа: `firstAfter + k * period` (`firstAfter` по умолчанию
  равен `period`). Опоздавшая проверка не сдвигает сетку, пропущенные точки не выполняются пачкой. Чтобы сдвинуть
  первую проверку, передайте `Interval` вместо `Duration`; выполнить её сразу при входе:
  `every(Interval.of(period).firstAfter(Duration.ZERO), handler, target)`.

### Таймеры процесса: `at`

`at(T)` ждёт таймер процесса: именованный момент (`TimerType`), который задаёт ваш код. Он нужен для срока, который
сдвигается (продление сообщением, срок из заказа), и для расписания по датам.

```java
static final TimerType PAYMENT_DUE = TimerType.of("paymentDue");

static void reserve(ProcessContext context) {                      // шаг перед ожиданием
    context.setTimer(PAYMENT_DUE, context.now().plus(Duration.ofDays(5)));
}

static void extend(ProcessContext context, IncomingMessage<Instant> message) {   // цель — само ожидание
    context.setTimer(PAYMENT_DUE, message.payload());
}
```

- Таймер задаётся `context.setTimer`, при запуске — `newProcess(...).timer(...)` или оператором, а отменяется
  `context.cancelTimer`. Изменение действует сразу, без повторного входа в ожидание.
- `at` срабатывает, когда процесс в его ожидании и момент таймера наступил; момент в прошлом срабатывает сразу.
- Переходы не трогают таймеры процесса. Таймер живёт, пока не сработает или не будет отменён, поэтому один срок
  может охватывать несколько ожиданий. Если после выхода из ожидания по другому событию таймер больше не нужен,
  отмените его в этом обработчике.
- Пока выполняется обработчик `at`, таймер хранит момент срабатывания. Обработчик, который сдвигает таймер, строит
  расписание; таймер, оставленный без изменений, отменяется после обработчика.

Расписание по датам:

```java
.await(SCHEDULED, await -> await
    .at(NEXT_RUN, ReportDefinition::buildReport, routes -> routes
        .on(Run.MORE, SCHEDULED)
        .on(Run.LAST, DONE)))

static Run buildReport(ProcessContext context) {
    var runAt = context.getTimer(NEXT_RUN).orElseThrow();         // момент срабатывания
    // построить отчёт за runAt
    context.setTimer(NEXT_RUN, runAt.plus(Duration.ofDays(1)));
    return Run.MORE;
}
```

Считайте следующий момент от момента срабатывания, а не от `context.now()`: тогда опоздавший запуск не сдвигает
расписание. Полные примеры — рецепты `deadline` и `subscription` в [`ruslo-examples/cookbook`](../../ruslo-examples/cookbook).

Если наступило сразу несколько событий, обрабатывается самое раннее; точный порядок — в
[execution.md](execution.md#выбор-события-ожидания).

## Сообщения

Тип сообщения задаёт имя и класс payload: `MessageType.of("payment", PaymentConfirmed.class)`. Обработчик получает
`IncomingMessage<T>` с payload и временем получения.

Сообщение принимается, только пока процесс находится в ожидании, которое объявляет его тип; ранние сообщения не
буферизуются. Что гарантирует и что бросает `sendMessage` — в [guarantees.md](guarantees.md#сообщения).

## Контекст процесса

Обработчик получает `ProcessContext`:

| Метод | Назначение |
|---|---|
| `getVariable`, `setVariable`, `removeVariable` | Переменные процесса |
| `getTimer`, `setTimer`, `cancelTimer` | Таймеры процесса для `at` |
| `processId()`, `processType()`, `state()`, `businessKey()`, `correlationId()` | Идентификация процесса |
| `attempt()` | Число неудачных попыток текущего шага или события |
| `now()` | Время движка; используйте вместо `Instant.now()`, чтобы им управляли тесты |

Изменения контекста сохраняются вместе с переходом.

Переменные и payload сообщений хранятся в JSON (Jackson). Их класс должен быть конкретным: оберните `List` или `Map`
в record (`record OrderItems(List<String> itemIds) {}`). Запущенные процессы читают сохранённый JSON текущим классом,
поэтому меняйте эти классы совместимо. Свой `ObjectMapper` задаётся через `ProcessEngineBuilder.withJsonMapper(...)`.

## Статус и ошибки

Процесс бывает `ACTIVE`, `FAILED` или `COMPLETED` (дошёл до terminal-состояния).

Исключение обработчика — техническая ошибка: его изменения откатываются, а дальнейшее решает политика ошибок.
Ожидаемые бизнес-ситуации выражайте результатами ветвящегося обработчика. См. [error-handling.md](error-handling.md).

## Обработчики

Definition строится один раз, и его обработчики вызываются многими процессами одновременно. Делайте их без
состояния, а данные процесса храните в `ProcessContext`.

В Spring наследуйте `SpringProcessDefinition` и берите обработчики через `bean(Type.class)` или внедряйте через
конструктор. Без Spring создайте экземпляры сами и передайте definition в `ProcessEngineBuilder`.

`ProcessState.description()` задаёт подпись состояния на диаграмме Admin UI; по умолчанию это `name()`.

## Эволюция definition

Движок хранит имена типа и состояний, но не версию definition. Запущенные процессы используют definition текущего
приложения:

- изменение обработчика затрагивает и запущенные процессы;
- процесс в удалённом или переименованном состоянии не может выполняться: он повторяется каждые 60 секунд с ошибкой
  в `lastError`.

Сохраняйте старые состояния, пока в них есть процессы. Если состояние всё же удалено, переведите затронутые процессы:

```java
processEngine.processes().byTypeName("ORDER").byStateName("OLD_STATE").list()
        .forEach(p -> processEngineAdmin.moveProcessToState(p.id(), OrderState.NEW_STATE));
```

## Ошибки компиляции

Тип обработчика связан с формой объявления, поэтому большинство ошибок видно при компиляции. Текст сообщения зависит
от версии `javac` и IDE; ориентируйтесь на симптом.

| Ошибка | Симптом | Исправление |
|---|---|---|
| Линейный шаг возвращает результат | `no suitable method found for step(...)`: `bad return type in lambda expression` | Объявите маршруты или ничего не возвращайте |
| У ветвящегося шага одна цель | `... cannot be converted to ProcessStep` | Замените цель на `routes -> routes.on(...)` |
| У линейного шага маршруты | `... cannot be converted to BranchingStep<O>` | Замените маршруты целевым состоянием |
| Маршруты используют другой enum | `Stock cannot be converted to Validation` | Используйте константы enum, который возвращает шаг |
| То же для события ожидания | те же симптомы с `message(...)`, `every(...)` | Те же исправления |

Не используйте raw types и приведения типов для обработчиков: они превращают эти ошибки компиляции в ошибки
выполнения.

`build()` проверяет остальное: повторяющиеся состояния и события, необъявленные цели, неполные маршруты,
неположительные задержки, ожидание без выхода и незарегистрированные worker.
