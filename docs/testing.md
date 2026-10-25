# Testing

English | [Русский](ru/testing.md)

Add `ruslo-spring-boot-starter-test` (see the [quickstart](quickstart.md#6-test-the-process-with-the-emulator))
and annotate the test with `@ProcessEngineTest`. The annotation:

- disables the background dispatcher: processes run only when you tell them to;
- registers `ProcessEngineEmulator`, which runs a process in the test thread;
- replaces the engine clock with `MutableClock`, so time can be moved through the emulator;
- resets the engine clock to the initial time before each test: Spring reuses the context between tests,
  and without the reset a time shift from one test would carry over to the next ones.

## ProcessEngineEmulator

| Method | What it does |
|---|---|
| `runUntilStop(id)` | Runs transitions while the process is ready: stops in an await with no due events, on a future timer or delayed retry, in `FAILED` or on completion. If after an error `fallback` or a retry without delay leads to a step, execution continues |
| `runOneStep(id)` | Runs one step or one due await event; throws `IllegalStateException` for a process in `FAILED` |
| `runUntilState(id, state)` | Runs transitions up to the given state; throws `IllegalStateException` if the process stops earlier |
| `runAll()` | Runs all active processes in the database to their next stop, for scenarios where processes interact. The database is shared by tests with the same context: active processes of previous tests are included too |
| `sendMessageAndRunUntilStop(id, type, payload)` | Calls `sendMessage`, then `runUntilStop`; throws `IllegalStateException` if the process did not accept the message |
| `fastForwardTime(duration)` | Moves the engine clock forward |
| `setTime(instant)` | Sets the current time of the engine clock |
| `resetTime()` | Resets the engine clock to the initial time; `@ProcessEngineTest` does this before each test |

The methods return `ProcessInstanceDetails`: check `state()`, `status()` and `lastError()`. Read variables in a
typed way: `details.variables().get(AMOUNT)` returns an `Optional` of the variable class.

## Timers and retries

`every` and `after` timers, process timers of `at` and `retryAfter` delays, of steps too, come due when the emulator moves time
(`fastForwardTime` or `setTime`): `runUntilStop` stops at a step waiting for a retry, `runOneStep` runs it at once.
The application's own `Clock` beans do not affect the engine clock:

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

        emulator.fastForwardTime(Duration.ofSeconds(11));   // the await waits after(10 seconds)

        var completed = emulator.runUntilStop(processId);
        assertEquals(ProcessStatus.COMPLETED, completed.status());
    }
}
```

A deadline of a process timer (`at`) is tested with `setTime`. Handlers take the time from `context.now()`, the
engine clock, so a step that sets the timer sees the time the test set. `details.timers()` shows the set timers:

```java
var start = Instant.parse("2026-10-07T10:00:00Z");
emulator.setTime(start);
var processId = processEngine.newProcess(SeatReservationDefinition.TYPE).start();
var waiting = emulator.runUntilStop(processId);      // the step sets PAYMENT_DUE = context.now() + 15 minutes
assertEquals(start.plus(SeatReservationDefinition.PAYMENT_TIME),
        waiting.timers().get(SeatReservationDefinition.PAYMENT_DUE.name()));

emulator.setTime(start.plus(SeatReservationDefinition.PAYMENT_TIME));
assertTrue(emulator.runUntilStop(processId).state().is(SeatReservationDefinition.State.EXPIRED));
```

A handler that calls `Instant.now()` instead sees the system time, and such a test fails.

Full examples in `ruslo-examples/cookbook`: the `deadline` recipe (`at` timer), the `subscription` recipe (schedule
by dates) and the `retries` recipe (retries and fallback state).

Control time through the emulator; there is no need to inject the engine clock into the test. To set your own
initial clock, declare a `MutableClock` bean (`core.api.test`) named `processEngineClock` with
`@Bean(defaultCandidate = false)` in the test configuration; it replaces the default clock. Without Spring, the
emulator is created from the engine: `new ProcessEngineEmulator(engine)`. It uses the engine clock, so to move time,
build the engine with a `MutableClock` (`ProcessEngineBuilder.withClock`); otherwise `fastForwardTime` and `setTime`
throw `IllegalStateException`.

To control time with the dispatcher running, use `@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)`
instead of `@ProcessEngineTest`: the emulator and `MutableClock` are registered, and the dispatcher stays enabled.

## Step unit tests: TestProcessContext

A step or handler can be tested without Spring, the engine or a database by passing it a `TestProcessContext`
(`core.api.test`, module `ruslo-core`). Unlike a mock, it stores variables the way the engine does:
a value is serialized to JSON on write and rebuilt on read. So the test catches values that do not survive
serialization, and changes to an object obtained from `getVariable` without a `setVariable` call, which the engine
loses.

```java
var context = TestProcessContext.builder()
        .variable(ORDER, new Order("PS5", new BigDecimal("500.00")))
        .attempt(1)                  // 0 is the first execution, then the retry number
        .build();

assertEquals(Outcome.PAID, paymentStep.execute(context));
assertEquals("p-1", context.getVariable(PAYMENT_ID));
assertEquals(Set.of("order", "paymentId"), context.variableNames());
```

| Builder method | Default |
|---|---|
| `processId(UUID)`, `correlationId(String)` | random |
| `processType(ProcessType)`, `state(ProcessState)` | `"test"` |
| `attempt(int)` | `0` |
| `businessKey(String)` | none |
| `now(Instant)` | `now()` from the system clock |
| `variable(VariableType<T>, T)`, `variable(String, Object)` | no variables |
| `timer(TimerType, Instant)` | no timers |
| `jsonMapper(ObjectMapper)` | the engine's default mapper; set the same one as in `ProcessEngineBuilder.withJsonMapper` |

`TestProcessContext.create()` is a context with default values. Besides the `ProcessContext` methods, it has
`variableNames()`, the names of all variables, and `timers()`, the set timers by name. Timer moments are truncated to
microseconds, as in the engine. The context knows no definition, so `setTimer` does not check that an `at` declares
the timer. Example: `CheckApplicationStepTest` of the `branching` recipe
in `ruslo-examples/cookbook`.

## What the emulator does not check

The emulator runs in one thread and does not reproduce worker routing, concurrency limits, node failures or races
between nodes. If these matter for your process, write an integration test with a running dispatcher on the same
database as in production.

Pass the definition the same step and handler instances the application uses: then the test checks the real
process graph.
