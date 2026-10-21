# Process model

English | [Русский](ru/concepts.md)

A process is a set of explicit states of three kinds:

- a **step** (`step`) performs an action and always moves on;
- an **await** (`await`) waits for a message or a timer;
- a **terminal** state completes the process.

The initial state is a step or an await.

The main rule: **the handler reports a result, the definition declares the target**. A handler never names the next
state: the whole transition graph, including await loops, is visible in one place.

```java
MessageType<String> APPROVED = MessageType.of("approved", String.class);

ProcessDefinition definition = builder -> builder.type("APPROVAL")
    .initialState(State.CREATE)
    .step(State.CREATE, context -> createOrder(context), State.WAIT)
    .await(State.WAIT, await -> await
        .message(APPROVED, (context, message) -> { }, State.DONE)
        .after(Duration.ofHours(1), State.EXPIRED))
    .terminal(State.DONE)
    .terminal(State.EXPIRED)
    .build();
```

A process is identified only by its type (`ProcessType`), which is also used to start it:
`processEngine.newProcess(type).start()`. Declare the type as a constant in the definition class: start, search
and tests then refer to the same value, and one definition class can be registered under different types:

```java
public class ApprovalProcessDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("APPROVAL");

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
            ...
    }
}

UUID processId = processEngine.newProcess(ApprovalProcessDefinition.TYPE).start();
```

## Steps

A linear step implements `ProcessStep` (`void execute(context)`); its target is the third argument of `step`.

A branching step implements `BranchingStep<O>` and returns a constant of enum `O`; the definition maps each
constant to a state:

```java
enum Validation { VALID, REJECTED }

BranchingStep<Validation> validateStep = context -> isValid(context) ? Validation.VALID : Validation.REJECTED;

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

The compiler checks that the step's enum matches the routes, and `build()` checks that all constants are covered.
A step whose target is itself runs again.

## Awaits

An await declares events:

| Event | Fires when | Handler |
|---|---|---|
| `message(type, handler, next \| routes)` | a message of type `type` arrives | `ProcessMessageHandler<T>` or `BranchingMessageHandler<T, O>` |
| `every(interval, handler, next \| routes)` | repeatedly, every `interval` on a grid from entering the await | `ProcessTimerHandler` or `BranchingTimerHandler<O>` |
| `after(delay[, handler], next \| routes)` | `delay` after entering the await | none, `ProcessTimerHandler` or `BranchingTimerHandler<O>` |
| `at(variable[, handler], next \| routes)` | at the moment stored in an `Instant` variable | none, `ProcessTimerHandler` or `BranchingTimerHandler<O>` |

At least one event must lead to another state.

```java
enum Payment { PAID, PENDING }

static final VariableType<Instant> DUE_DATE = VariableType.of("dueDate", Instant.class);

.await(WAITING_FOR_PAYMENT, await -> await
    .every(Duration.ofSeconds(30), checkPayment, routes -> routes   // polling an external condition
        .on(Payment.PAID, SHIPPING)
        .on(Payment.PENDING, WAITING_FOR_PAYMENT))                 // keep waiting
    .message(EXTEND_DUE_DATE, extendDueDate, WAITING_FOR_PAYMENT)  // writes DUE_DATE
    .after(Duration.ofDays(3), remind, WAITING_FOR_PAYMENT)         // one reminder
    .at(DUE_DATE, markExpired, CANCELLED))
```

Timer rules:

1. `every(interval)` fires repeatedly, on the grid `entering the await + initialDelay + k * interval`: a late check
   does not shift the next ones, missed points are skipped. `after(delay)` fires `delay` after entering the await.
   `at(variable)` fires at the moment stored in the variable: no variable, no timer; the variable changed, the timer
   moved. The variable is always an `Instant`, in every state and at start: another value is rejected on write.
2. Each timer is declared at most once per await; any of them can be combined.
3. **A timer fires once per moment.** The moment of `after` does not change within one entry, so it fires once per
   entry. After firing, `at` waits for a new value of its variable: a handler that writes the next date makes a
   schedule; a handler that does not write it leaves the await waiting for its other events. The process does not
   fail in that case: it is the same as an await whose message never comes. "Once" holds within one entry: entering
   the await again with the same past moment fires `at` right away, so a loop "await → step → await" must move the
   moment or leave.
4. A handler error pauses the whole await until the retry: a due timer waits until the failed event is handled
   (see [execution.md](execution.md#await-event-selection)).

A timer without a handler cannot target the await itself: it would do nothing. The first `every` check can run
right on entering the await: `every(interval, handler, next, options -> options.initialDelay(Duration.ZERO))`.

A moment for `at` is computed by a step or handler from `context.now()`, e.g.
`context.setVariable(DUE_DATE, context.now().plus(Duration.ofMinutes(15)))`, or passed by the caller at start.
A schedule by dates is an `at` whose handler writes the next date; the first date is written by the caller at start
or by a step before the await:

```java
.step(PLAN, ReportDefinition::planFirstRun, SCHEDULED)          // writes NEXT_RUN
.await(SCHEDULED, await -> await
    .at(NEXT_RUN, ReportDefinition::buildReport, routes -> routes
        .on(Run.MORE, SCHEDULED)                                // wait for the next date
        .on(Run.LAST, DONE))
    .message(CANCEL, ReportDefinition::onCancel, CANCELLED))
```

**An event whose target is the await itself means "keep waiting".** This is not a re-entry: the handled message is
removed, the other messages and the timers are kept (the `every` schedule, the `after` and `at` moments), and no
state history row is written. Use it for:

- a periodic side effect: `every(Duration.ofDays(1), sendReminder, WAITING_FOR_PAYMENT)`;
- intermediate messages: `message(STATUS_UPDATE, saveStatus, WAITING_FOR_DELIVERY)`.

Any transition to another state closes the await and removes unhandled messages. To start the await over, with an
empty queue and a new `after` deadline, go to a step that leads back to the await. A deadline that must move
without re-entry is an `at`: write the new moment to its variable.

If several events are due, the earliest one is handled; the exact rules are in [execution.md](execution.md#await-event-selection).

## Messages

The first argument of `message` declares the name and the payload class. The handler receives an
`IncomingMessage<T>` with the id, type, payload and receive time.

A message is accepted only while the process is in an await that declares it. Early and late messages are not
stored: `sendMessage` throws `MessageNotExpectedException`; there is no buffering. Every step before an await is
committed separately, so between the process start and entering the await there is a window when a message is
rejected. Delivery guarantees are described in [guarantees.md](guarantees.md#messages).

## Process context

A handler receives a `ProcessContext`:

| Method | Purpose |
|---|---|
| `getVariable(type)`, `setVariable(type, value)`, `removeVariable(type)` | Process variables; changes are saved with the transition |
| `processId()`, `processType()`, `state()` | Process identity |
| `businessKey()` | Business key set at start through `newProcess(...).businessKey(...)` |
| `correlationId()` | Id for logs: set through `newProcess(...).correlationId(...)`, otherwise taken from the caller thread's MDC or generated |
| `attempt()` | Number of previous failed attempts in the current state |
| `now()` | Current time of the engine clock, the one timers use; use it instead of `Instant.now()`, so the emulator controls it in tests |

Variables and message payloads are stored in the database as JSON (Jackson). Process variables are kept in the
`xs_process_instance.variables` column as one JSON object, e.g. `{"orderId":"A-1","amount":42}`;
`ProcessQuery.byVariablesLike("%\"orderId\":\"A-1\"%")` searches it. Change their classes compatibly: running
processes will read the stored JSON with the new class. A custom `ObjectMapper` is set through
`ProcessEngineBuilder.withJsonMapper(...)`.

`MessageType<T>` and `VariableType<T>` take a non-empty name and a class: `MessageType.of("payment", PaymentConfirmed.class)`
or `MessageType<PaymentConfirmed> payment = MessageType.of("payment")`. The class must be concrete, without type
parameters: `List`, `Map` and other generic classes are rejected with `IllegalArgumentException`. Wrap a collection
in a record:

```java
record OrderItems(List<String> itemIds) {}
VariableType<OrderItems> ITEMS = VariableType.of("items", OrderItems.class);
```

## Status and errors

Process status is `ACTIVE`, `FAILED` or `COMPLETED`. `COMPLETED` means the process reached a terminal state.

A handler exception is a technical error: changes are rolled back, and `ProcessErrorHandler` decides what happens
next (retry, stop in `FAILED` or go to the fallback state). Express expected business situations as an outcome of
a branching handler. Details: [error-handling.md](error-handling.md).

## State description

`ProcessState.description()` sets a human-readable label that the Admin UI shows on the process diagram.
It defaults to `name()`. The description cannot be empty and affects neither the state identity nor execution:

```java
enum OrderState implements ProcessState {
    CREATED("Creating the order"),
    WAITING_FOR_PAYMENT("Waiting for payment");

    private final String description;

    OrderState(String description) { this.description = description; }

    @Override
    public String description() { return description; }
}
```

## Handler lifecycle

A definition is built once, when it is registered in the engine, and its steps and handlers are called
concurrently by many processes. Make them stateless or thread-safe; keep per-process data in `ProcessContext`,
not in handler fields.

In Spring, handlers are passed to the definition through constructor injection or through `bean(Type.class)` of the
`SpringProcessDefinition` base class. Without Spring, instances are created explicitly before the definition is
registered in `ProcessEngineBuilder`.

## Compiler errors

The handler type (`ProcessStep` / `BranchingStep<O>`, `ProcessMessageHandler<T>` / `BranchingMessageHandler<T, O>`,
`ProcessTimerHandler` / `BranchingTimerHandler<O>`) is tied to the form of the declaration in the definition, so
most mistakes show up at compile time. Message text depends on the `javac` and IDE version; go by the symptom.

| Mistake | Symptom | Fix |
|---|---|---|
| A linear step lambda returns an outcome | `no suitable method found for step(...)`: `bad return type in lambda expression` | Declare routes (the step becomes branching) or return nothing |
| A branching step is declared with a single target | `no suitable method found for step(...)`: `ValidateStep cannot be converted to ProcessStep` | Replace the target with `routes -> routes.on(...)` |
| A linear step is declared with routes | `no suitable method found for step(...)`: `CreateStep cannot be converted to BranchingStep<O>` | Replace the routes with the target state |
| Routes use an outcome of another enum | `Stock cannot be converted to Validation` | Use constants of the enum the step returns |
| An await event handler | the same symptoms with `message(...)` / `every(...)` | As for steps: routes for an outcome, a target state for a linear handler |

`build()` checks the rest of the structure: duplicate states and events, declared targets, complete routes,
positive delays and an exit from every await.

## Rules for users and AI agents

- Do not use raw types or casts on handlers: they bypass the compiler checks, and a wrong handler form shows up only
  at runtime.
- A handler does not name the target state. A step always leaves as the definition says; only an await can stay,
  through an event whose target is the await itself.
- To add a branch, replace `ProcessStep` with `BranchingStep<Outcome>` and return an outcome: the compiler points to
  the place in the definition where the target must become routes. The same for await events:
  `ProcessMessageHandler<T>` → `BranchingMessageHandler<T, Outcome>`, `ProcessTimerHandler` → `BranchingTimerHandler<Outcome>`.
- Retry after a technical error with `ErrorResult.retryAfter`, not with a step that repeats itself.
- Do periodic checks with `every` in an await, not with a "step → await → step" loop: re-entering the await clears
  the queue and resets `after`. A schedule by calendar dates is an `at` whose handler writes the next date.

## Definition evolution

The engine stores the type and state names, but not the definition version. Running processes use the definition
registered in the current application:

- a handler change affects running processes too;
- removing or renaming a state (or a type) leaves running processes in the old state, which the engine cannot
  execute. Such a process is postponed by 60 seconds with an error in `lastError` until it is fixed.

The simplest approach is to keep old states while running processes are in them. If a state was removed anyway, find
the affected processes and move them to a registered state:

```java
processEngine.processes().byTypeName("ORDER").byStateName("OLD_STATE").list()
        .forEach(p -> processEngineAdmin.moveProcessToState(p.id(), OrderState.NEW_STATE));
```

The move clears the message queue; the variables must suit the new handler. For a removed type, first bring its
definition back: `moveProcessToState` does not change the type.
