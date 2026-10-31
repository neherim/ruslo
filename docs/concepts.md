# Process model

English | [Русский](ru/concepts.md)

A process definition (`ProcessDefinition`) is a set of transitions between states (`ProcessState`) of three kinds:

- a **step** (`step`) runs code and always moves on;
- an **await** (`await`) waits for a message or a timer;
- a **terminal** state completes the process.

The whole transition graph is visible in one place.

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

The process type (`ProcessType`) identifies the definition: it is used to start processes, search and test them.

## Steps

Steps (`step`) come in two kinds. A linear step implements `ProcessStep`.
After `ProcessStep.execute()` returns, the engine moves the process to the next state.

A branching step implements `BranchingStep<O>` and returns a constant of enum `O`; the engine maps each
constant to a state:

```java
enum Validation { VALID, REJECTED }

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

The compiler checks that the routes use the step's enum; at runtime `build()` checks that every constant is routed.

## Awaits

An await (`await`) declares the events it waits for:

| Event | Fires |
|---|---|
| `message(type, handler, target)` | when a message of `type` arrives |
| `every(interval, handler, target)` | repeatedly, every `interval` |
| `after(delay[, handler], target)` | once, `delay` after entering the await |
| `at(timer[, handler], target)` | at the moment of the process timer `timer` |

`target` is the target state of the process or the mapping of the handler's outcome to process states
(`routes -> routes.on(...)`). An await declares each timer kind at most once.

```java
.await(WAITING_FOR_PAYMENT, await -> await
    .message(PAYMENT_CONFIRMED, PaymentDefinition::onPaid, SHIPPING)
    .every(Duration.ofDays(1), PaymentDefinition::remind, WAITING_FOR_PAYMENT)   // a daily reminder
    .at(PAYMENT_DUE, CANCELLED))                                               // a deadline set by the code
```

### Staying in an await

An event whose target is the await itself keeps the process waiting: the handled message is removed, other queued
messages and the timers are kept.

A transition to another state closes the await, and **its unhandled messages are removed**. To start an await over,
with an empty queue and new timers, go through a step that leads back to it:

```java
.await(ACTIVE, await -> await
    .message(ACTIVITY, Session::onActivity, RECORDING_ACTIVITY)   // an ACTIVITY message restarts the timeout
    .after(Duration.ofMinutes(30), EXPIRED))
.step(RECORDING_ACTIVITY, Session::recordActivity, ACTIVE)
```

### Await timers: `every` and `after`

`every` and `after` belong to one entry into the await; the engine sets them when the process enters it.

- `after(delay)` fires once, `delay` after entering.
- `every(period)` fires on a fixed grid from entering: `firstAfter + k * period` (`firstAfter` defaults to
  `period`). A late check does not shift the grid, and missed points are not run in a batch. To move the first
  check, pass an `Interval` instead of a `Duration`. For example, to run `every` right on entering:
  `every(Interval.of(period).firstAfter(Duration.ZERO), handler, target)`.

### Process timers: `at`

`at(T)` waits for a named process timer (`TimerType`). User code controls the timer: it can set, move or cancel
it:

```java
static final TimerType PAYMENT_DUE = TimerType.of("paymentDue");

static void reserve(ProcessContext context) {                      // a step before the await
    context.setTimer(PAYMENT_DUE, context.now().plus(Duration.ofDays(5)));
}

static void extend(ProcessContext context, IncomingMessage<Instant> message) {   // targets the await itself
    context.setTimer(PAYMENT_DUE, message.payload());
}
```

A process timer consists of a name and a fire moment. The name is given by `TimerType`:
`TimerType.of("paymentDue")`.

1. **A timer belongs to the process.** A process can have several timers with different names. A timer with one
   name is either not set or set to one fire moment; `setTimer` on a set timer replaces its fire moment.
2. **Code and operators set and cancel the timer.** At process start with `ProcessEngine.newProcess(...).timer(...)`,
   in handlers with `ProcessContext.setTimer` and `ProcessContext.cancelTimer`, manually in the Admin UI. A change
   takes effect at once, without entering the await again. The engine never sets a timer; it only cancels a fired
   timer (rule 5).
3. **A timer fires only in an await with `at`.** A timer fires when the process is in an await with `at` and the
   fire moment is not later than the engine time (`ProcessContext.now()`). If the fire moment has already passed when
   the process enters such an await, the timer fires at once. A timer that is not set does not fire.
4. **Transitions do not change the timer.** A timer stays set until it fires or is cancelled. This holds also when
   the process leaves an await with `at` by another event of that await (`message`, `after`, `every`): the timer then
   fires in the next await with `at` for the same name.
5. **A fired timer is cancelled after the `at` handler.** While the `at` handler runs, the timer stays set and
   `getTimer` returns its fire moment. After the handler the engine cancels the timer unless the handler set a new
   fire moment; the same moment does not count as new. If the handler set a new moment, the timer stays set to it.
   If the handler fails, its changes are rolled back and the retry sees the timer unchanged. For an `at` without a
   handler the timer is cancelled when it fires.

Moving the timer in the handler makes a schedule:

```java
.await(SCHEDULED, await -> await
    .at(NEXT_RUN, ReportDefinition::buildReport, routes -> routes
        .on(Run.MORE, SCHEDULED)
        .on(Run.LAST, DONE)))

static Run buildReport(ProcessContext context) {
    var runAt = context.getTimer(NEXT_RUN).orElseThrow();         // the fired moment
    // build the report for runAt
    context.setTimer(NEXT_RUN, runAt.plus(Duration.ofDays(1)));
    return Run.MORE;
}
```

Count the next moment from the fired one, not from `context.now()`: a late run then does not shift the schedule.
Full examples: the `deadline` and `subscription` recipes in [`ruslo-examples/cookbook`](../ruslo-examples/cookbook).

If several events are due at once, the earliest one is handled; the exact order is in
[execution.md](execution.md#await-event-selection).

## Messages

A message type declares the name and the payload class: `MessageType.of("payment", PaymentConfirmed.class)`.
The handler receives an `IncomingMessage<T>` with the payload and the receive time.

A message is accepted only while the process is in an await that declares its type; there is no buffering of early
messages. What `sendMessage` guarantees and what it throws: [guarantees.md](guarantees.md#messages).

## Process context

A handler receives a `ProcessContext`:

| Method | Purpose |
|---|---|
| `getVariable`, `setVariable`, `removeVariable` | Process variables |
| `getTimer`, `setTimer`, `cancelTimer` | Process timers for `at` |
| `processId()`, `processType()`, `state()`, `businessKey()`, `correlationId()` | Process identity |
| `attempt()` | Number of failed attempts of the current step or event |
| `now()` | Engine time; use it instead of `Instant.now()`, so that tests control it |

Context changes are saved together with the transition.

Variables and message payloads are stored as JSON (Jackson). Their class must be concrete: wrap a `List` or `Map` in
a record (`record OrderItems(List<String> itemIds) {}`). Running processes read stored JSON with the current class,
so change these classes compatibly. A custom `ObjectMapper` is set with `ProcessEngineBuilder.withJsonMapper(...)`.

## Status and errors

A process can be `ACTIVE`, `FAILED` or `COMPLETED`.

A handler exception is a technical error: by default the database transaction is rolled back and the error policy decides what
happens next. Express expected business situations as outcomes of a branching handler. See
[error-handling.md](error-handling.md).

## Definition evolution

The engine stores the type and state names, but not the definition version. Running processes use the definition
of the current application:

- a handler change affects running processes too;
- a process in a removed or renamed state cannot run: it is retried every 60 seconds with an error in `lastError`.

Keep old states while processes are in them. If a state was removed anyway, move the affected processes.

Next: [guarantees](guarantees.md), [error handling](error-handling.md), [Spring Boot](spring.md),
[configuration](configuration.md), [testing](testing.md).
