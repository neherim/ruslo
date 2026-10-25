# Тестирование

[English](../testing.md) | Русский

Подключите `ruslo-spring-boot-starter-test` (см. [быстрый старт](quickstart.md#6-проверьте-процесс-эмулятором))
и пометьте тест `@ProcessEngineTest`. Аннотация:

- отключает фоновый диспетчер — процессы исполняются только по вашей команде;
- регистрирует `ProcessEngineEmulator`, который исполняет процесс в потоке теста;
- заменяет часы движка на `MutableClock`, чтобы перематывать время через эмулятор;
- перед каждым тестом возвращает часы движка к начальному времени: Spring переиспользует контекст между тестами,
  и без сброса перемотка из одного теста сохранялась бы в следующих.

## ProcessEngineEmulator

| Метод | Что делает |
|---|---|
| `runUntilStop(id)` | Исполняет переходы, пока процесс готов: останавливается в ожидании без наступивших событий, на будущем таймере или повторе с задержкой, в `FAILED` или при завершении. Если после ошибки `fallback` или повтор без задержки ведут в шаг, исполнение продолжается |
| `runOneStep(id)` | Исполняет один шаг или одно наступившее событие ожидания; для процесса в `FAILED` бросает `IllegalStateException` |
| `runUntilState(id, state)` | Исполняет переходы до указанного состояния; бросает `IllegalStateException`, если процесс остановился раньше |
| `runAll()` | Доводит все активные процессы в БД до ближайшей остановки — для сценариев, где процессы взаимодействуют. Учитывайте, что база общая для тестов с одним контекстом: сюда попадут и активные процессы предыдущих тестов |
| `sendMessageAndRunUntilStop(id, type, payload)` | Вызывает `sendMessage`, затем `runUntilStop`; если процесс сообщение не принял, бросает `IllegalStateException` |
| `fastForwardTime(duration)` | Перематывает часы движка вперёд |
| `setTime(instant)` | Устанавливает текущее время часов движка |
| `resetTime()` | Возвращает часы движка к начальному времени; `@ProcessEngineTest` делает это перед каждым тестом сам |

Методы возвращают `ProcessInstanceDetails`: проверяйте `state()`, `status()` и `lastError()`. Переменные
читайте типизированно: `details.variables().get(AMOUNT)` возвращает `Optional` значения класса переменной.

## Таймеры и повторы

Таймеры `every` и `after`, таймеры процесса для `at` и задержки `retryAfter`, в том числе у шагов, наступают, когда время перемотано
эмулятором (`fastForwardTime` или `setTime`): `runUntilStop` останавливается на шаге, ждущем повтора, `runOneStep`
выполняет его сразу. Собственные бины `Clock` приложения на часы движка не влияют:

```java
@ProcessEngineTest
@SpringBootTest
class OrderServiceTest {
    @Autowired
    private OrderService orderService;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void orderAutoCompletesAfterDeliveryTimeout() {
        var processId = orderService.createOrder(new CreateOrderRequest("PS5", 1));
        emulator.runUntilStop(processId);
        orderService.confirmPayment(processId, new PaymentConfirmed(BigDecimal.valueOf(500)));

        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(State.WAITING_FOR_DELIVERY_TIMER));

        emulator.fastForwardTime(Duration.ofSeconds(11));   // ожидание ждёт after(10 секунд)

        var completed = emulator.runUntilStop(processId);
        assertEquals(ProcessStatus.COMPLETED, completed.status());
    }
}
```

Срок таймера процесса (`at`) тестируется через `setTime`. Обработчики берут время из `context.now()` — часов
движка, поэтому шаг, ставящий таймер, видит время, заданное тестом. Поставленные таймеры показывает
`details.timers()`:

```java
var start = Instant.parse("2026-10-07T10:00:00Z");
emulator.setTime(start);
var processId = processEngine.newProcess(SeatReservationDefinition.TYPE).start();
var waiting = emulator.runUntilStop(processId);      // шаг ставит PAYMENT_DUE = context.now() + 15 минут
assertEquals(start.plus(SeatReservationDefinition.PAYMENT_TIME),
        waiting.timers().get(SeatReservationDefinition.PAYMENT_DUE.name()));

emulator.setTime(start.plus(SeatReservationDefinition.PAYMENT_TIME));
assertTrue(emulator.runUntilStop(processId).state().is(SeatReservationDefinition.State.EXPIRED));
```

Обработчик, вызывающий `Instant.now()`, видит системное время, и такой тест падает.

Полные примеры в `ruslo-examples/cookbook`: рецепты `deadline` (таймер `at`), `subscription` (расписание по датам)
и `retries` (повторы и запасное состояние).

Временем управляйте через эмулятор, внедрять часы движка в тест не нужно. Чтобы задать собственные начальные часы,
объявите в тестовой конфигурации бин `MutableClock` (`core.api.test`) с именем `processEngineClock` и
`@Bean(defaultCandidate = false)` — он заменит часы по умолчанию. Без Spring эмулятор создаётся из движка:
`new ProcessEngineEmulator(engine)`. Он использует часы движка, поэтому для перемотки времени создайте движок с `MutableClock`
(`ProcessEngineBuilder.withClock`), иначе `fastForwardTime` и `setTime` бросят `IllegalStateException`.

Чтобы управлять временем при работающем диспетчере, подключите вместо `@ProcessEngineTest`
`@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)`: эмулятор и `MutableClock` появятся, а диспетчер
останется включённым.

## Юнит-тесты шагов: TestProcessContext

Шаг или обработчик можно проверить без Spring, движка и базы данных, передав ему `TestProcessContext`
(`core.api.test`, модуль `ruslo-core`). В отличие от мока, он хранит переменные так же, как движок:
значение сериализуется в JSON при записи и собирается заново при чтении. Поэтому тест ловит значения, которые
не переживают сериализацию, и изменения объекта, полученного из `getVariable`, без вызова `setVariable` —
в движке такие изменения теряются.

```java
var context = TestProcessContext.builder()
        .variable(ORDER, new Order("PS5", new BigDecimal("500.00")))
        .attempt(1)                  // 0 — первое исполнение, далее — номер повтора
        .build();

assertEquals(Outcome.PAID, paymentStep.execute(context));
assertEquals("p-1", context.getVariable(PAYMENT_ID));
assertEquals(Set.of("order", "paymentId"), context.variableNames());
```

| Метод builder | По умолчанию |
|---|---|
| `processId(UUID)`, `correlationId(String)` | случайные |
| `processType(ProcessType)`, `state(ProcessState)` | `"test"` |
| `attempt(int)` | `0` |
| `businessKey(String)` | нет |
| `now(Instant)` | `now()` по системным часам |
| `variable(VariableType<T>, T)`, `variable(String, Object)` | без переменных |
| `timer(TimerType, Instant)` | без таймеров |
| `jsonMapper(ObjectMapper)` | mapper движка по умолчанию; укажите тот же, что в `ProcessEngineBuilder.withJsonMapper` |

`TestProcessContext.create()` — контекст со значениями по умолчанию. Помимо методов `ProcessContext` есть
`variableNames()` — имена всех переменных, и `timers()` — поставленные таймеры по имени. Моменты таймеров усекаются
до микросекунд, как в движке. Контекст не знает определения, поэтому `setTimer` не проверяет, что таймер объявлен в
`at`. Пример: `CheckApplicationStepTest` рецепта `branching` в
`ruslo-examples/cookbook`.

## Что эмулятор не проверяет

Эмулятор работает в одном потоке и не воспроизводит маршрутизацию по workers, лимиты конкурентности, падение узлов
и гонки между узлами. Если это важно для вашего процесса, напишите интеграционный тест с запущенным диспетчером
на той же СУБД, что и в production.

Передавайте в definition те же экземпляры шагов и обработчиков, что использует приложение: тогда тест проверяет
настоящий граф процесса.
