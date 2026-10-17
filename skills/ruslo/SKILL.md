---
name: ruslo
description: Write, test and debug business processes on Ruslo, a Java 21 / Spring Boot library for durable stateful processes backed by a relational database. Use when the code imports io.github.neherim.ruslo, or when the task touches ProcessDefinition, ProcessSpecBuilder, ProcessStep, BranchingStep, await/message/every/timeout, ProcessEngine.newProcess/sendMessage, ProcessErrorHandler, workers, @ProcessEngineTest or ProcessEngineEmulator.
---

# Ruslo

Written for Ruslo 0.1.x. Full docs: https://github.com/neherim/ruslo/tree/main/docs.
Copyable recipes with tests: https://github.com/neherim/ruslo/tree/main/ruslo-examples/cookbook.

## Mental model

A process is a graph of explicit states of three kinds:

- **step**: runs code and always leaves for the next state;
- **await**: waits for a message, a periodic timer (`every`) or a deadline (`timeout`);
- **terminal**: completes the process.

**Handlers report a result, the definition declares the target.** A handler never names the next state.
A linear handler returns nothing and has one target; a branching handler returns an enum outcome and the
definition maps every outcome to a state.

Every transition (one handler call + new state + `ProcessContext` changes) is committed in its own
transaction. On failure only the uncommitted handler runs again. External calls are therefore
**at-least-once**: make them idempotent.

## Defining a process

```java
public enum OrderState implements ProcessState {
    CREATED, WAITING_FOR_PAYMENT, DELIVERING, CANCELLED, COMPLETED
}

public class Constants {
    public static final VariableType<String> CUSTOMER_ID = VariableType.of("customerId", String.class);
    public static final MessageType<PaymentConfirmed> PAYMENT_CONFIRMED =
            MessageType.of("paymentConfirmed", PaymentConfirmed.class);
    public static final MessageType<String> ORDER_CANCELLED = MessageType.of("orderCancelled", String.class);
}

@Component
public class OrderProcessDefinition extends SpringProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER");

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(5)), CANCELLED)
            .initialState(CREATED)
            .step(CREATED, bean(CreateOrderStep.class), WAITING_FOR_PAYMENT)
            .await(WAITING_FOR_PAYMENT, await -> await
                .message(PAYMENT_CONFIRMED, bean(PaymentHandlers.class)::onPaid, DELIVERING)
                .message(ORDER_CANCELLED, bean(PaymentHandlers.class)::onCancelled, CANCELLED)
                .timeout(Duration.ofMinutes(15), CANCELLED))
            .step(DELIVERING, bean(DeliverOrderStep.class), COMPLETED)
            .terminal(COMPLETED)
            .terminal(CANCELLED)
            .build();
    }
}
```

- States are an enum implementing `ProcessState`; names are persisted. Override `description()` for a
  human-readable label in the Admin UI.
- Declare `ProcessType`, `MessageType<T>` and `VariableType<T>` as constants and reuse them everywhere
  (start, search, tests).
- Without Spring, implement `ProcessDefinition` and pass handler instances explicitly; register it with
  `ProcessEngineBuilder`.

### Handler types

| Declaration | Linear handler (one target) | Branching handler (`routes -> routes.on(...)`) |
|---|---|---|
| `step(state, handler, ...)` | `ProcessStep`: `void execute(ProcessContext)` | `BranchingStep<O>`: returns `O` |
| `message(type, handler, ...)` | `ProcessMessageHandler<T>`: `(ctx, IncomingMessage<T>) -> void` | `BranchingMessageHandler<T, O>` |
| `every(interval, handler, ...)` | `ProcessTimerHandler` | `BranchingTimerHandler<O>` |
| `timeout(delay[, handler], next)` | optional `ProcessTimerHandler` | not allowed |

Branching example:

```java
enum Validation { VALID, REJECTED }

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

To add a branch, change `ProcessStep` to `BranchingStep<Outcome>`; the compiler then points at the definition
line where the target must become routes. `build()` checks that all enum constants are routed.

### Await rules

- At most one `every` and one `timeout` per await; at least one event must lead to another state.
- **An event whose target is the await itself means "keep waiting"**: the handled message is removed,
  other queued messages and the `timeout` deadline are kept. Use it for reminders
  (`every(Duration.ofDays(1), sendReminder, WAITING)`) and intermediate messages.
- Any transition to another state closes the await and **drops unprocessed messages**.
- `timeout` is a hard deadline; its target cannot be the await itself.
- Polling an external system: `every` with branching routes back to the same await. Do not build a
  "step → await → step" loop: re-entering an await clears the queue and resets the `timeout`.
- Run the first `every` check immediately: `every(interval, handler, next, o -> o.initialDelay(Duration.ZERO))`.

### ProcessContext

`getVariable(type)` (nullable), `setVariable(type, value)`, `removeVariable(type)`, `processId()`,
`processType()`, `state()`, `businessKey()`, `correlationId()`, `attempt()`.

- Variables and message payloads are stored as JSON (Jackson). Their class must be concrete and non-generic:
  wrap a `List`/`Map` in a record (`record OrderItems(List<String> ids) {}`).
- Mutating an object returned by `getVariable` without calling `setVariable` is lost.
- Change variable classes compatibly: running processes will read old JSON with the new class.

## Starting processes and sending messages

```java
UUID id = processEngine.newProcess(OrderProcessDefinition.TYPE)
        .businessKey(orderId)
        .variable(Constants.CUSTOMER_ID, customerId)
        .start();                       // returns right after insert; steps run asynchronously

processEngine.sendMessage(id, Constants.PAYMENT_CONFIRMED, new PaymentConfirmed(txId));
```

- Inside Spring `@Transactional`, `start()` and `sendMessage()` are atomic with the application's own changes
  (same `DataSource` / transaction manager).
- A message is accepted **only while the process is in an await that declares its type**. Otherwise
  `sendMessage` throws `MessageNotExpectedException` (with `getStatus()` and `getCurrentState()`) and stores
  nothing.
  **There is no buffering of early messages**: the sender retries later or ignores it.
- `MessageNotExpectedException` is thrown after the engine's transaction, so catching it does **not** mark the
  caller's transaction rollback-only. Catch it where a late or duplicate message is a normal case
  (webhooks, reports to a finished parent):

  ```java
  try {
      processEngine.sendMessage(processId, CALLBACK, callback);
  } catch (MessageNotExpectedException ex) {
      log.info("Callback ignored in state {}", ex.getCurrentState().name());
  }
  ```
- A message type not declared by any await of the process is a caller bug: `IllegalArgumentException`.
- `sendMessage` is **not idempotent**: deduplicate in the handler, e.g. by an event id in the payload.
- `sendMessageSync(...)` runs the message handler in the caller's thread and transaction; handler exceptions
  propagate to the caller. A `FAILED` process is not handled synchronously: `MessageNotExpectedException` with
  `getStatus() == FAILED`, nothing is stored.
- Find processes: `processEngine.processes().byType(TYPE).byBusinessKey(key).single()` (`Optional`),
  `.byId(id).singleDetails()`, `.list()`.

## Errors

- An exception from a handler is a **technical failure**: its transaction rolls back and the process-level
  `ProcessErrorHandler` decides: `ErrorResult.retryAfter(delay)`, `fail()` (status `FAILED`) or `fallback()`
  (go to the fallback state given in `onError(handler, fallbackState)`, else `FAILED`).
- Expected business situations (declined card, rejected application) are **outcomes of a branching handler**,
  not exceptions.
- Built-ins: `ProcessErrorHandler.noRetry()` (default), `fixed(maxRetries, delay)`,
  `exponential(maxRetries, initialDelay, maxDelay)`. With a fallback state, `noRetry()` goes there on the
  first error.
- Retry is `ErrorResult.retryAfter`, never a step whose target is itself.
- A `FAILED` process does not run; continue it with `processEngine.resumeFailedProcess(id)`.
  `ProcessEngineAdmin.moveProcessToState(id, state)` moves it manually.

## Workers

- `DEFAULT` worker is transactional: step logic and engine persistence share one transaction, so the step's
  own DB writes on the same `DataSource` happen exactly once. All message and timer handlers run here.
- For long external I/O, put the call in a **separate step** on a named `non_transactional` worker
  (`step(state, handler, next, o -> o.worker(IO_WORKER))`) and make the call idempotent, e.g. pass
  `context.processId()` as the provider's idempotency key. Do not write to the application DB there:
  it is not atomic with the transition.
- Register workers in `application.yml`:

  ```yaml
  ruslo:
    workers:
      io-worker:
        execution-mode: non_transactional
        max-concurrency: 50
  ```

## Testing (always do this after changing a process)

Dependency: `io.github.neherim:ruslo-spring-boot-starter-test` (test scope).

```java
@ProcessEngineTest
@SpringBootTest
class OrderProcessTest {
    @Autowired OrderService orderService;
    @Autowired ProcessEngineEmulator emulator;

    @Test
    void cancelsWhenNotPaidInTime() {
        var id = orderService.createOrder("customer-1");
        assertTrue(emulator.runUntilStop(id).state().is(OrderState.WAITING_FOR_PAYMENT));

        emulator.fastForwardTime(Duration.ofMinutes(16));

        var details = emulator.runUntilStop(id);
        assertTrue(details.state().is(OrderState.CANCELLED));
        assertEquals(ProcessStatus.COMPLETED, details.status());
    }
}
```

- `@ProcessEngineTest` disables the dispatcher; processes run only through the emulator, in the test thread.
- Emulator: `runUntilStop(id)`, `runOneStep(id)`, `runUntilState(id, state)`, `runAll()`,
  `sendMessageAndRunUntilStop(id, type, payload)`, `fastForwardTime(d)`, `setTime(instant)`.
  Results are `ProcessInstanceDetails`: check `state()`, `status()`, `lastError()`,
  `variables().get(TYPE)` (`Optional`).
- Control time **only** through the emulator; the application's own `Clock` beans do not affect engine timers.
- Unit-test a single handler with `TestProcessContext.builder().variable(TYPE, value).attempt(1).build()`;
  it round-trips variables through JSON like the engine does.
- Test failures: make a dependency throw, then assert `status() == FAILED` / the fallback state and
  `lastError()`; advance time past `retryAfter` delays with `fastForwardTime`.

## Do not

- Use raw types or casts on handlers: they bypass the compile-time checks of the handler/declaration shape.
- Store per-process data in handler fields: handlers are shared and called concurrently. Use `ProcessContext`.
- Throw exceptions for expected business outcomes; use a branching handler.
- Expect a message sent before the process reaches the await to be delivered later.
- Import packages outside `io.github.neherim.ruslo.core.api.*`, `core.spi.*` and `io.github.neherim.ruslo.spring.*`:
  everything else is internal.
- Remove or rename a state while live processes may be in it: they get stuck (retried every 60 s with
  `lastError`). Keep the old state, or move them with `ProcessEngineAdmin.moveProcessToState` first.
- Assume parallel branches inside one process: steps of one process never run in parallel. Fan out into
  child processes and collect their reports via messages (cookbook recipe `fanout`).

## Compile errors and what they mean

| Symptom | Fix |
|---|---|
| `no suitable method found for step(...)`: `bad return type in lambda expression` | Linear step returns a value: declare routes or return nothing |
| `... cannot be converted to ProcessStep` | Branching handler with a single target: replace target with `routes -> routes.on(...)` |
| `... cannot be converted to BranchingStep<O>` | Linear handler with routes: replace routes with a target state |
| `X cannot be converted to Y` inside routes | Routes use constants of another enum than the handler returns |
| Same symptoms with `message(...)` / `every(...)` | Same fixes as for steps |

`build()` validates the rest (duplicate states/events, undeclared targets, incomplete routes, non-positive
delays, await without exit, unregistered workers) and its message names the problem.

## Where to look

| Need | Doc | Cookbook recipe |
|---|---|---|
| Branching, awaits, timers | `docs/concepts.md` | `branching`, `deadline`, `polling`, `approval`, `subscription` |
| Errors, retries, compensation | `docs/error-handling.md` | `retries`, `failandresume`, `saga` |
| Delivery guarantees, messages | `docs/guarantees.md` | `webhook`, `externalcall` |
| Transactions, event ordering | `docs/execution.md` | `transactionalstart`, `cancellation`, `batch` |
| Workers | `docs/workers.md` | `ioworker` |
| Testing | `docs/testing.md` | `testing` |
| Spring properties | `docs/configuration.md` | |
