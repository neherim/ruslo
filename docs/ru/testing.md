# Тестирование

Подключите `ruslo-spring-boot-starter-test` (см. [быстрый старт](quickstart.md#6-проверьте-процесс-эмулятором))
и пометьте тест `@ProcessEngineTest`. Аннотация:

- выключает фоновый диспетчер: процессы выполняются, только когда это велит тест;
- регистрирует `ProcessEngineEmulator`, который выполняет процессы в потоке теста;
- даёт движку часы, которыми управляет эмулятор, и сбрасывает их перед каждым тестом.

## ProcessEngineEmulator

| Метод | Что делает |
|---|---|
| `runUntilStop(id)` | Выполняет переходы, пока процессу есть что делать: останавливается в ожидании без наступивших событий, на будущем таймере или повторе, в `FAILED` или при завершении |
| `runOneStep(id)` | Выполняет один шаг или одно наступившее событие ожидания |
| `runUntilState(id, state)` | Выполняет переходы до заданного состояния; если процесс остановился раньше, бросает исключение |
| `runAll()` | Доводит все активные процессы до следующей остановки |
| `sendMessageAndRunUntilStop(id, type, payload)` | `sendMessage`, затем `runUntilStop` |
| `fastForwardTime(duration)`, `setTime(instant)` | Двигают часы движка |

Методы возвращают `ProcessInstanceDetails`. Сравнивайте состояние с объявленной константой, ожидаемое значение
первым:

```java
assertEquals(OrderState.COMPLETED, details.state(), details::toString);
```

При несовпадении сообщение покажет фактическое состояние, а `details::toString` добавит статус, `lastError`,
последнюю историю, переменные и таймеры. Переменные читаются через `details.variables().get(AMOUNT)`.

`runAll()` видит все процессы в базе, в том числе оставшиеся от предыдущих тестов с тем же Spring-контекстом.

## Таймеры и повторы

Таймеры и задержки `retryAfter` наступают, только когда тест двигает часы движка:

```java
@ProcessEngineTest
@SpringBootTest
class OrderServiceTest {
    @Autowired
    private OrderService orderService;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void cancelsOrderWhenNotPaidInTime() {
        var processId = orderService.createOrder("customer-1");
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(Duration.ofHours(3));   // заказ из README ждёт after(2 hours)

        var cancelled = emulator.runUntilStop(processId);
        assertEquals(OrderState.CANCELLED, cancelled.state());
    }
}
```

## Юнит-тесты обработчика: TestProcessContext

Шаг или обработчик можно проверить без Spring, движка и базы данных с `TestProcessContext` (`ruslo-core`). В отличие
от мока, он хранит переменные в JSON, как движок, поэтому тест ловит значения, которые не переживают сериализацию, и
объекты, изменённые без `setVariable`.

```java
var context = TestProcessContext.builder()
        .variable(ORDER, new Order("PS5", new BigDecimal("500.00")))
        .attempt(1)
        .build();

assertEquals(Outcome.PAID, paymentStep.execute(context));
assertEquals("p-1", context.getVariable(PAYMENT_ID));
```

Builder также задаёт id, тип и состояние процесса, business key, `now()` и таймеры.

## Что эмулятор не проверяет

Эмулятор работает в одном потоке: он не воспроизводит маршрутизацию по workers, лимиты конкурентности, падения
узлов и гонки между узлами. Если они важны, напишите интеграционный тест с работающим диспетчером на базе как в
production.

## Дополнительная настройка

- Чтобы диспетчер работал, а временем всё равно управлял тест, используйте
  `@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)` вместо `@ProcessEngineTest`.
- Чтобы начать со своего начального времени, объявите в тестовой конфигурации бин `MutableClock` с именем
  `processEngineClock` и `@Bean(defaultCandidate = false)`.
- Без Spring постройте движок с `MutableClock` (`ProcessEngineBuilder.withClock`) и создайте эмулятор через
  `new ProcessEngineEmulator(engine)`.
