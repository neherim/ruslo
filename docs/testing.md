# Testing

English | [Русский](ru/testing.md)

Add `ruslo-spring-boot-starter-test` (see the [quickstart](quickstart.md#6-test-the-process-with-the-emulator))
and annotate the test with `@ProcessEngineTest`. The annotation:

- disables the background dispatcher: processes run only when the test tells them to;
- registers `ProcessEngineEmulator`, which runs processes in the test thread;
- gives the engine a clock that the emulator controls, reset before each test.

## ProcessEngineEmulator

| Method | What it does |
|---|---|
| `runUntilStop(id)` | Runs transitions while the process has something to do: stops in an await with no due events, at a future timer or retry, in `FAILED` or on completion |
| `runOneStep(id)` | Runs one step or one due await event |
| `runUntilState(id, state)` | Runs transitions up to the given state; throws with the process summary if the process stops earlier |
| `runAll()` | Runs all active processes to their next stop, for processes that interact |
| `sendMessageAndRunUntilStop(id, type, payload)` | `sendMessage`, then `runUntilStop` |
| `fastForwardTime(duration)`, `setTime(instant)` | Move the engine clock |

The methods return `ProcessInstanceDetails`. Compare the state by id, expected value first:

```java
assertEquals(OrderState.COMPLETED.id(), details.state(), details::toString);
```

On a mismatch the message shows the actual state, and `details::toString` adds the status, `lastError`, recent
history, variables and timers. Read variables with `details.variables().get(AMOUNT)`.

`runAll()` sees every process in the database, also those left by previous tests that share the Spring context.

## Timers and retries

Timers and `retryAfter` delays come due only when the test moves the engine clock:

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

        emulator.fastForwardTime(Duration.ofHours(3));   // the order from the README waits after(2 hours)

        var cancelled = emulator.runUntilStop(processId);
        assertEquals(OrderState.CANCELLED.id(), cancelled.state());
    }
}
```

For an `at` deadline, set the time first, so the step that sets the timer from `context.now()` sees it:

```java
var start = Instant.parse("2026-10-07T10:00:00Z");
emulator.setTime(start);
var processId = processEngine.newProcess(SeatReservationDefinition.TYPE).start();
emulator.runUntilStop(processId);                    // the step sets PAYMENT_DUE = now + PAYMENT_TIME

emulator.setTime(start.plus(SeatReservationDefinition.PAYMENT_TIME));
assertEquals(SeatReservationDefinition.State.EXPIRED.id(), emulator.runUntilStop(processId).state());
```

Handlers must take the time from `context.now()`: a handler that calls `Instant.now()` does not see the test time.
The application's own `Clock` beans do not affect the engine.

Full examples in [`ruslo-examples/cookbook`](../ruslo-examples/cookbook): the `deadline`, `subscription` and
`retries` recipes.

## Unit tests of a handler: TestProcessContext

A step or handler can be tested without Spring, the engine or a database with a `TestProcessContext` (`ruslo-core`).
Unlike a mock, it stores variables as JSON, as the engine does, so the test catches values that do not survive
serialization and objects changed without `setVariable`.

```java
var context = TestProcessContext.builder()
        .variable(ORDER, new Order("PS5", new BigDecimal("500.00")))
        .attempt(1)
        .build();

assertEquals(Outcome.PAID, paymentStep.execute(context));
assertEquals("p-1", context.getVariable(PAYMENT_ID));
```

The builder also sets the process id, type, state, business key, `now()`, timers and the `ObjectMapper`; use the same
mapper as the engine. Example: `CheckApplicationStepTest` of the `branching` recipe.

## What the emulator does not check

The emulator runs in one thread: it does not reproduce worker routing, concurrency limits, node failures or races
between nodes. If they matter, write an integration test with a running dispatcher on the production database.

## Advanced setup

- To run the dispatcher and still control time, use `@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)`
  instead of `@ProcessEngineTest`.
- To start from your own initial time, declare a `MutableClock` bean named `processEngineClock` with
  `@Bean(defaultCandidate = false)` in the test configuration.
- Without Spring, build the engine with a `MutableClock` (`ProcessEngineBuilder.withClock`) and create the emulator
  with `new ProcessEngineEmulator(engine)`.
