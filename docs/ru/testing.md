# Тестирование

Подключите `ruslo-spring-boot-starter-test` (см. [быстрый старт](quickstart.md#6-проверьте-процесс-эмулятором))
и пометьте тест `@ProcessEngineTest`. Аннотация:

- выключает фоновый диспетчер: процессы выполняются, только когда это велит тест;
- регистрирует `ProcessEngineEmulator`, который выполняет процессы в потоке теста;
- настраивает для движка изменяемый источник времени, которым может управлять эмулятор.

По умолчанию `@ProcessEngineTest` сбрасывает используемый движком `MutableTimeSource` перед каждым тестом.
Чтобы сохранять время между тестовыми методами, используйте `@ProcessEngineTest(resetTimeBeforeEachTest = false)`.
При необходимости можно явно сбросить его через `emulator.resetTime()`.

## ProcessEngineEmulator

| Метод | Что делает |
|---|---|
| `runUntilStop(id)` | Выполняет переходы, пока процессу есть что делать: останавливается в ожидании без наступивших событий, на будущем таймере или повторе, в `FAILED` или при завершении |
| `runOneStep(id)` | Выполняет один шаг или одно наступившее событие ожидания |
| `runUntilState(id, state)` | Выполняет переходы до заданного состояния; если процесс остановился раньше, бросает исключение |
| `runAll()` | Доводит все активные процессы до следующей остановки |
| `sendMessageAndRunUntilStop(id, type, payload)` | `sendMessage`, затем `runUntilStop` |

Методы возвращают `ProcessInstanceDetails`. Сравнивайте состояние с объявленной константой, ожидаемое значение
первым:

```java
assertEquals(OrderState.COMPLETED, details.state(), details::toString);
```

При несовпадении сообщение покажет фактическое состояние, а `details::toString` добавит статус, `lastError`,
последнюю историю, переменные и таймеры. Переменные читаются через `details.variables().find(AMOUNT)`.

`runAll()` видит все процессы в базе, в том числе оставшиеся от предыдущих тестов с тем же Spring-контекстом.

## Таймеры и повторы

Переведите часы к сроку таймера или повтора через `emulator.fastForwardTime(duration)` или
`emulator.setTime(instant)`, затем вызовите `runUntilStop(id)`. Эмулятор и движок проверяют готовность
с точностью до микросекунд.

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

Эмулятор также присоединяется к существующей транзакции, в том числе к транзакции теста со Spring `@Transactional`.
Его переходы тогда выполняются в общей транзакции вместо независимых фиксаций, как у настоящих workers. Ошибка
обработчика может пометить общую Spring-транзакцию как rollback-only, а откат теста отменяет и предыдущие переходы.
При проверке повторов и откатов вызывайте эмулятор вне внешней транзакции; для проверки транзакционных границ
production используйте работающий диспетчер.

## Дополнительная настройка

- Чтобы диспетчер работал, а временем всё равно управлял тест, используйте
  `@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)` вместо `@ProcessEngineTest`.
  Эта конфигурация также оставляет сброс часов тесту; при необходимости вызывайте `emulator.resetTime()`.
- Чтобы начать со своего начального времени, объявите в тестовой конфигурации бин `MutableTimeSource` и создайте
  его через `new MutableTimeSource(initialTime)`.
- Без Spring передайте `MutableTimeSource` в `ProcessEngineBuilder.withTimeSource(...)` и создайте эмулятор через
  `new ProcessEngineEmulator(engine)`.
