# Модель процесса

[English](../concepts.md) | Русский

Описание процесса (`ProcessDefinition`) - это набор переходов между состояниями (`ProcessState`) трёх видов:

- **шаг** (`step`) выполняет код и всегда переходит дальше;
- **ожидание** (`await`) ждёт сообщения или таймера;
- **terminal** завершает процесс.

Весь граф переходов виден в одном месте.

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

Тип процесса (`ProcessType`) идентифицирует definition: по нему процессы запускают, ищут и тестируют.

## Шаги

Шаги (`step`) бывают двух типов. Линейный шаг реализует `ProcessStep`.
После исполнения метода `ProcessStep.execute()` движок переводит процесс в следующее состояние.

Ветвящийся шаг реализует `BranchingStep<O>` и возвращает константу enum `O`, а движок сопоставляет каждую
константу с состоянием:

```java
enum Validation { VALID, REJECTED }

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

Компилятор проверяет, что маршруты используют enum шага, в runtime при вызове метода `build()` проверяется, что для каждой константы задан маршрут.

## Ожидания

Ожидание (`await`) объявляет события, которых ждёт:

| Событие | Срабатывает |
|---|---|
| `message(type, handler, target)` | когда приходит сообщение типа `type` |
| `every(interval, handler, target)` | регулярно, каждые `interval` |
| `after(delay[, handler], target)` | один раз, через `delay` после входа в ожидание |
| `at(timer[, handler], target)` | в момент таймера процесса `timer` |

`target` - целевое состояние процесса или список соответствий результата, который вернул обработчик,
состоянию процесса (`routes -> routes.on(...)`). Каждый вид таймера объявляется в ожидании не больше одного раза.

```java
.await(WAITING_FOR_PAYMENT, await -> await
    .message(PAYMENT_CONFIRMED, PaymentDefinition::onPaid, SHIPPING)
    .every(Duration.ofDays(1), PaymentDefinition::remind, WAITING_FOR_PAYMENT)   // ежедневное напоминание
    .at(PAYMENT_DUE, CANCELLED))                                               // срок, который задаёт код
```

### Остаться в ожидании

Событие, целью которого указано само ожидание, оставляет процесс ждать: обработанное сообщение удаляется, остальные
сообщения в очереди и таймеры сохраняются.

Переход в другое состояние закрывает ожидание, и **его необработанные сообщения удаляются**. Чтобы начать ожидание
заново, с пустой очередью и новыми таймерами, пройдите через шаг, который ведёт обратно в него:

```java
.await(ACTIVE, await -> await
    .message(ACTIVITY, Session::onActivity, RECORDING_ACTIVITY)   // сообщение ACTIVITY перезапускает таймаут
    .after(Duration.ofMinutes(30), EXPIRED))
.step(RECORDING_ACTIVITY, Session::recordActivity, ACTIVE)
```

### Таймеры ожидания: `every` и `after`

`every` и `after` относятся к одному входу в ожидание; движок задаёт их, когда процесс входит в него.

- `after(delay)` срабатывает один раз, через `delay` после входа.
- `every(period)` срабатывает по фиксированной сетке от входа: `firstAfter + k * period` (`firstAfter` по умолчанию
  равен `period`). Опоздавшая проверка не сдвигает сетку, пропущенные точки не выполняются пачкой. Чтобы сдвинуть
  первую проверку, передайте `Interval` вместо `Duration`. Например, если мы хотим выполнить `every` сразу при входе:
  `every(Interval.of(period).firstAfter(Duration.ZERO), handler, target)`.

### Таймеры процесса: `at`

`at(T)` ждёт именованный таймер процесса (`TimerType`). Таймером можно управлять из пользовательского кода, 
сдвигать его, отменять, устанавливать новое значение:

```java
static final TimerType PAYMENT_DUE = TimerType.of("paymentDue");

static void reserve(ProcessContext context) {                      // шаг перед ожиданием
    context.setTimer(PAYMENT_DUE, context.now().plus(Duration.ofDays(5)));
}

static void extend(ProcessContext context, IncomingMessage<Instant> message) {   // цель — само ожидание
    context.setTimer(PAYMENT_DUE, message.payload());
}
```

Таймер процесса состоит из имени и момента срабатывания. Имя задаётся через `TimerType`:
`TimerType.of("paymentDue")`.

1. **Таймер принадлежит процессу.** У процесса может быть несколько таймеров с разными именами. Таймер с одним
   именем либо не задан, либо задан на один момент срабатывания; `setTimer` для заданного таймера заменяет его
   момент срабатывания.
2. **Таймер задают и отменяют код и оператор.** При запуске процесса — `ProcessEngine.newProcess(...).timer(...)`,
   в обработчиках — `ProcessContext.setTimer` и `ProcessContext.cancelTimer`, вручную — в Admin UI. Изменение
   действует сразу, без повторного входа в ожидание. Движок сам таймер не задаёт; он только отменяет сработавший
   таймер (пункт 5).
3. **Таймер срабатывает только в ожидании с `at`.** Таймер срабатывает, когда процесс находится в ожидании с `at`,
   а момент срабатывания не позже времени движка (`ProcessContext.now()`). Если момент срабатывания уже прошёл к
   моменту входа в такое ожидание, таймер срабатывает сразу. Пока таймер не задан, он не срабатывает.
4. **Переходы не меняют таймер.** Таймер остаётся заданным, пока не сработает или не будет отменён. Это верно и
   когда процесс вышел из ожидания с `at` по другому событию этого ожидания (`message`, `after`, `every`): тогда
   таймер сработает в следующем ожидании с `at` с тем же именем.
5. **Сработавший таймер отменяется после обработчика `at`.** Пока выполняется обработчик `at`, таймер остаётся
   заданным, и `getTimer` возвращает его момент срабатывания. После обработчика движок отменяет таймер, если
   обработчик не задал ему новый момент срабатывания; тот же момент новым не считается. Если обработчик задал новый
   момент, таймер остаётся заданным на него. Если обработчик завершился ошибкой, его изменения откатываются, и
   повторная попытка видит таймер таким же. У `at` без обработчика таймер отменяется при срабатывании.

Сдвигая таймер в обработчике можно построить расписание:

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

Процесс может находиться в состоянии `ACTIVE`, `FAILED` или `COMPLETED`.

Исключение в обработчике - техническая ошибка: по умолчанию транзакция в БД откатывается, а дальнейшее решает политика ошибок.
Ожидаемые бизнес-ситуации выражайте результатами ветвящегося обработчика. См. [error-handling.md](error-handling.md).

## Эволюция definition

Движок хранит имена типа и состояний, но не версию definition. Запущенные процессы используют definition текущего
приложения:

- изменение обработчика затрагивает и запущенные процессы;
- процесс в удалённом или переименованном состоянии не может выполняться: он повторяется каждые 60 секунд с ошибкой
  в `lastError`.

Сохраняйте старые состояния, пока в них есть процессы. Если состояние всё же удалено, переведите затронутые процессы.

Дальше: [гарантии](guarantees.md), [обработка ошибок](error-handling.md), [Spring Boot](spring.md),
[конфигурация](configuration.md), [тестирование](testing.md).
