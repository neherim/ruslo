# Quickstart

English | [Русский](ru/quickstart.md)

This tutorial builds an order process: create the order → wait for payment → deliver → complete.
The full runnable code is in the [quickstart module](../ruslo-examples/quickstart).
All Java snippets below are taken from its sources; imports are omitted.
You need Java 21, Spring Boot 4 and a JDBC DataSource.

## 1. Add dependencies

In a Spring Boot application, add the starter and the JDBC driver of your database. The local example uses H2:

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

Set `spring.datasource.url`, `username` and `password`. On startup the starter creates the engine tables and starts
the dispatcher.

## 2. Declare the states

```java
public enum OrderState implements ProcessState {
    CREATED,
    WAITING_FOR_PAYMENT,
    DELIVERING,
    CANCELLED,
    COMPLETED
}
```

The enum implements `ProcessState`; state names are stored in the database.
`COMPLETED` and `CANCELLED` will be terminal states.

## 3. Implement steps and message handlers

A step performs an action, and its next state is declared in the definition:

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

The delivery step:

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

Declare a concrete payload and typed keys:

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

Message handlers also only perform an action; the transition target is declared in the definition:

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

## 4. Connect the states in a definition

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

`SpringProcessDefinition.bean(...)` gets the Spring beans when the definition is registered.
You can also implement `ProcessDefinition` and pass handlers through constructor injection.
Handlers are called by many processes concurrently: make them stateless and keep order data in `ProcessContext`.

## 5. Start a process and send a message

The example service creates a process through `ProcessStartBuilder` and stores the customer id in a variable:

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

`createOrder` returns the `processId` right after the process is saved; steps run asynchronously.
Once the process is in `WAITING_FOR_PAYMENT`, call `confirmPayment(processId, "txn-42")` or `cancelOrder`.
A message the process is not waiting for (the await has not started yet or the process has completed) is not stored:
`sendMessage` throws `MessageNotExpectedException` with the current process state, and the sender decides whether to
retry later or ignore it. The exception is thrown after the engine's transaction, so catching it does not roll back
the application transaction. Inside `@Transactional`, starting a process and sending a message are atomic with the
application data. Details: [guarantees](guarantees.md#messages).

The current state is available through `processEngine.processes().byId(processId).singleDetails()`.

## 6. Test the process with the emulator

Add test dependencies:

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

`@ProcessEngineTest` disables the dispatcher and provides an emulator that runs processes in the calling thread:

```java
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(waiting.state().is(OrderState.WAITING_FOR_PAYMENT));

        orderService.confirmPayment(processId, "txn-42");

        var completed = emulator.runUntilStop(processId);
        assertTrue(completed.state().is(OrderState.COMPLETED));
    }

    @Test
    void cancelsOrderAfterCancelMessage() {
        var processId = orderService.createOrder("customer-2");

        emulator.runUntilStop(processId);
        orderService.cancelOrder(processId, "customer request");

        var cancelled = emulator.runUntilStop(processId);
        assertTrue(cancelled.state().is(OrderState.CANCELLED));
        assertEquals("customer request",
                cancelled.variables().get(Constants.CANCELLATION_REASON).orElseThrow());
    }
}
```

From the repository root, run:

```bash
./mvnw test -pl ruslo-examples/quickstart -am
```

Next: [process model](concepts.md), [guarantees](guarantees.md),
[error handling](error-handling.md), [testing](testing.md).
