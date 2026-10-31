# Быстрый старт

[English](../quickstart.md) | Русский

В этом туториале создадим процесс заказа: создать заказ → дождаться оплаты → доставить → завершить.
Полный исполняемый код находится в [модуле quickstart](../../ruslo-examples/quickstart).
Все Java-сниппеты ниже взяты из его исходников; импорты опущены.
Нужны Java 21, Spring Boot 4 и JDBC DataSource.

## 1. Подключите зависимости

В Spring Boot приложении добавьте starter и JDBC-драйвер своей БД. Для локального примера используется H2:

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>runtime</scope>
</dependency>
```

Настройте `spring.datasource.url`, `username` и `password`. Starter при запуске создаст таблицы движка
и запустит диспетчер.

## 2. Объявите состояния

```java
public enum OrderState implements ProcessState {
    CREATED,
    WAITING_FOR_PAYMENT,
    DELIVERING,
    CANCELLED,
    COMPLETED
}
```

Enum реализует `ProcessState`; имена состояний сохраняются в БД.
`COMPLETED` и `CANCELLED` будут terminal-состояниями.

## 3. Реализуйте шаги и сообщения

Шаг выполняет действие, а его следующее состояние задаётся в definition:

```java
@Component
public class CreateOrderStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(CreateOrderStep.class);

    /** Creates the order; the process then waits for payment. */
    @Override
    public void execute(ProcessContext context) {
        var customerId = context.getVariable(Constants.CUSTOMER_ID);
        log.info("Creating order for customer {}", customerId);
    }
}
```

Шаг доставки:

```java
@Component
public class DeliverOrderStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(DeliverOrderStep.class);

    /** Delivers the order; the process then completes. */
    @Override
    public void execute(ProcessContext context) {
        log.info("Delivering order for process {}", context.processId());
    }
}
```

Объявите конкретный payload и типизированные ключи:

```java
public record PaymentConfirmed(String transactionId) {
}
```

```java
public class Constants {
    public static final VariableType<String> CUSTOMER_ID = VariableType.of("customerId");
    public static final VariableType<String> CANCELLATION_REASON = VariableType.of("cancellationReason");
    public static final MessageType<PaymentConfirmed> PAYMENT_CONFIRMED = MessageType.of("paymentConfirmed");
    public static final MessageType<String> ORDER_CANCELLED = MessageType.of("orderCancelled");
}
```

Обработчики сообщений также выполняют действие; цель перехода задаётся в definition:

```java
/** Event handlers of the waiting-for-payment state. */
@Component
public class WaitingForPaymentHandlers {
    /**
     * Accepts the payment confirmation. Nothing else to do: the definition declares the transition to delivery,
     * so the handler may be empty.
     */
    public void onPaymentConfirmed(ProcessContext context, IncomingMessage<PaymentConfirmed> message) {
    }

    /** Stores the cancellation reason. */
    public void onCancelled(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(Constants.CANCELLATION_REASON, message.payload());
    }
}
```

## 4. Свяжите состояния в definition

```java
import static io.github.neherim.ruslo.examples.quickstart.Constants.ORDER_CANCELLED;
import static io.github.neherim.ruslo.examples.quickstart.Constants.PAYMENT_CONFIRMED;
import static io.github.neherim.ruslo.examples.quickstart.OrderState.*;

@Component
public class OrderProcessDefinition extends SpringProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER");

    /** Describes the states of a basic order process. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(CREATED)
            .step(CREATED, bean(CreateOrderStep.class), WAITING_FOR_PAYMENT)
            .await(WAITING_FOR_PAYMENT, await -> await
                .message(PAYMENT_CONFIRMED, bean(WaitingForPaymentHandlers.class)::onPaymentConfirmed, DELIVERING)
                .message(ORDER_CANCELLED, bean(WaitingForPaymentHandlers.class)::onCancelled, CANCELLED))
            .step(DELIVERING, bean(DeliverOrderStep.class), COMPLETED)
            .terminal(COMPLETED)
            .terminal(CANCELLED)
            .build();
    }
}
```

`SpringProcessDefinition.bean(...)` берёт обработчики из Spring-контекста ([spring.md](spring.md)). Обработчики
общие для всех процессов: делайте их без состояния, а данные заказа храните в `ProcessContext`.

## 5. Запустите процесс и отправьте сообщение

Сервис примера создаёт процесс через `ProcessStartBuilder` и сохраняет идентификатор клиента в переменной:

```java
@Service
@RequiredArgsConstructor
public class OrderService {
    private final ProcessEngine processEngine;

    /**
     * Creates an order and starts its process.
     *
     * @return the process id
     */
    public UUID createOrder(String customerId) {
        return processEngine.newProcess(OrderProcessDefinition.TYPE)
                .businessKey(customerId)
                .variable(Constants.CUSTOMER_ID, customerId)
                .start();
    }

    /**
     * Confirms the order payment.
     *
     * @param orderId the order process id
     * @throws MessageNotExpectedException if the process is not waiting for a payment now
     */
    public void confirmPayment(UUID orderId, String transactionId) {
        processEngine.sendMessage(orderId, Constants.PAYMENT_CONFIRMED, new PaymentConfirmed(transactionId));
    }

    /**
     * Cancels the order.
     *
     * @param orderId the order process id
     * @throws MessageNotExpectedException if the process does not accept a cancellation now
     */
    public void cancelOrder(UUID orderId, String reason) {
        processEngine.sendMessage(orderId, Constants.ORDER_CANCELLED, reason);
    }
}
```

`createOrder` возвращает `processId` сразу после сохранения процесса; шаги выполняются асинхронно.
После входа в `WAITING_FOR_PAYMENT` вызовите `confirmPayment(processId, "txn-42")` или `cancelOrder`.
Сообщение, которое процесс сейчас не ждёт, отклоняется с `MessageNotExpectedException`, см.
[гарантии](guarantees.md#сообщения).

Текущее состояние доступно через `processEngine.processes().byId(processId).singleDetails()`.

## 6. Проверьте процесс эмулятором

Добавьте тестовые зависимости:

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test</artifactId>
    <scope>test</scope>
</dependency>
```

`@ProcessEngineTest` отключает диспетчер и предоставляет эмулятор для выполнения в вызывающем потоке:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;

@ProcessEngineTest
@SpringBootTest(classes = Main.class)
class QuickstartOrderTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void completesOrderAfterPaymentMessage() {
        var processId = orderService.createOrder("customer-1");

        var waiting = emulator.runUntilStop(processId);
        assertEquals(OrderState.WAITING_FOR_PAYMENT.id(), waiting.state());

        orderService.confirmPayment(processId, "txn-42");

        var completed = emulator.runUntilStop(processId);
        assertEquals(OrderState.COMPLETED.id(), completed.state());
    }

    @Test
    void cancelsOrderAfterCancelMessage() {
        var processId = orderService.createOrder("customer-2");

        emulator.runUntilStop(processId);
        orderService.cancelOrder(processId, "customer request");

        var cancelled = emulator.runUntilStop(processId);
        assertEquals(OrderState.CANCELLED.id(), cancelled.state());
        assertEquals("customer request",
                cancelled.variables().get(Constants.CANCELLATION_REASON).orElseThrow());
    }
}
```

Из корня репозитория выполните:

```bash
./mvnw test -pl ruslo-examples/quickstart -am
```

Дальше: [модель процесса](concepts.md), [гарантии](guarantees.md),
[обработка ошибок](error-handling.md), [Spring Boot](spring.md), [тестирование](testing.md).
