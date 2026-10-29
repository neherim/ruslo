# Process model

English | [Русский](ru/concepts.md)

A process is a set of explicit states of three kinds:

- a **step** (`step`) runs code and always moves on;
- an **await** (`await`) waits for a message or a timer;
- a **terminal** state completes the process.

The main rule: **a handler reports a result, the definition declares the target.** A handler never names the next
state, so the whole transition graph is visible in one place.

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
Declare it, the message types and the variable types as constants and refer to them everywhere.

## Steps

A linear step implements `ProcessStep` (`void execute(context)`); its target is the third argument of `step`.

A branching step implements `BranchingStep<O>` and returns a constant of enum `O`; the definition maps each
constant to a state:

```java
enum Validation { VALID, REJECTED }

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

The compiler checks that the routes use the step's enum, and `build()` checks that every constant is routed.

A step whose target is itself runs again: a loop over batches, each batch committed in its own transition
(the `batch` recipe).

## Awaits

An await declares the events it waits for:

| Event | Fires | Handler |
|---|---|---|
| `message(type, handler, target)` | when a message of `type` arrives | required |
| `every(interval, handler, target)` | repeatedly, every `interval` | required |
| `after(delay[, handler], target)` | once, `delay` after entering the await | optional |
| `at(timer[, handler], target)` | at the moment of the process timer `timer` | optional |

`target` is a state or, for a branching handler, `routes -> routes.on(...)`. An await declares each timer kind at
most once, and at least one of its events leads to another state.

```java
.await(WAITING_FOR_PAYMENT, await -> await
    .message(PAYMENT_CONFIRMED, PaymentDefinition::onPaid, SHIPPING)
    .every(Duration.ofDays(1), PaymentDefinition::remind, WAITING_FOR_PAYMENT)   // a daily reminder
    .at(PAYMENT_DUE, CANCELLED))                                               // a deadline set by the code
```

### Staying in an await

An event whose target is the await itself keeps the process waiting: the handled message is removed, other queued
messages and the timers are kept. Use it for reminders, polling an external system and intermediate messages
(`message(STATUS_UPDATE, saveStatus, WAITING_FOR_DELIVERY)`).

A transition to another state closes the await, and **its unhandled messages are removed**. To start an await over,
with an empty queue and new timers, go through a step that leads back to it:

```java
.await(ACTIVE, await -> await
    .message(ACTIVITY, Session::onActivity, RECORDING_ACTIVITY)   // every activity restarts the timeout
    .after(Duration.ofMinutes(30), EXPIRED))
.step(RECORDING_ACTIVITY, Session::recordActivity, ACTIVE)
```

The same rule applies to steps and to manual moves: a transition to the current state is not a state change.

### Await timers: `every` and `after`

`every` and `after` belong to one entry into the await; the engine sets them when the process enters it.

- `after(delay)` fires once, `delay` after entering.
- `every(period)` fires on a fixed grid from entering: `firstAfter + k * period` (`firstAfter` defaults to
  `period`). A late check does not shift the grid, and missed points are not run in a batch. To move the first
  check, pass an `Interval` instead of a `Duration`; to run it right on entering:
  `every(Interval.of(period).firstAfter(Duration.ZERO), handler, target)`.

### Process timers: `at`

`at(T)` waits for a process timer: a named moment (`TimerType`) that your code sets. Use it for a deadline that
moves (extension by a message, a due date from the order) or a schedule by dates.

```java
static final TimerType PAYMENT_DUE = TimerType.of("paymentDue");

static void reserve(ProcessContext context) {                      // a step before the await
    context.setTimer(PAYMENT_DUE, context.now().plus(Duration.ofDays(5)));
}

static void extend(ProcessContext context, IncomingMessage<Instant> message) {   // targets the await itself
    context.setTimer(PAYMENT_DUE, message.payload());
}
```

- The timer is set by `context.setTimer`, at start by `newProcess(...).timer(...)` or by an operator, and cancelled
  by `context.cancelTimer`. A change takes effect at once, without entering the await again.
- `at` fires when the process is in its await and the timer is due; a moment in the past fires right away.
- Transitions do not touch process timers. A timer lives until it fires or is cancelled, so one deadline can cover
  several awaits. If a timer is no longer needed after the process leaves the await by another event, cancel it in
  that handler.
- While the `at` handler runs, the timer keeps the fired moment. A handler that moves the timer makes a schedule;
  a timer left unchanged is cancelled after the handler.

A schedule by dates:

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

A process is `ACTIVE`, `FAILED` or `COMPLETED` (it reached a terminal state).

A handler exception is a technical error: its changes are rolled back and the error policy decides what happens
next. Express expected business situations as outcomes of a branching handler. See
[error-handling.md](error-handling.md).

## Handlers

A definition is built once, and its handlers are called by many processes concurrently. Keep them stateless and
keep per-process data in `ProcessContext`.

In Spring, extend `SpringProcessDefinition` and take handlers with `bean(Type.class)`, or inject them through the
constructor. Without Spring, create the instances yourself and pass the definition to `ProcessEngineBuilder`.

`ProcessState.description()` sets the label of a state on the Admin UI diagram; it defaults to `name()`.

## Definition evolution

The engine stores the type and state names, but not the definition version. Running processes use the definition
of the current application:

- a handler change affects running processes too;
- a process in a removed or renamed state cannot run: it is retried every 60 seconds with an error in `lastError`.

Keep old states while processes are in them. If a state was removed anyway, move the affected processes:

```java
processEngine.processes().byTypeName("ORDER").byStateName("OLD_STATE").list()
        .forEach(p -> processEngineAdmin.moveProcessToState(p.id(), OrderState.NEW_STATE));
```

## Compiler errors

The handler type is tied to the form of the declaration, so most mistakes show up at compile time. The message
text depends on the `javac` and IDE version; go by the symptom.

| Mistake | Symptom | Fix |
|---|---|---|
| A linear step returns an outcome | `no suitable method found for step(...)`: `bad return type in lambda expression` | Declare routes or return nothing |
| A branching step has a single target | `... cannot be converted to ProcessStep` | Replace the target with `routes -> routes.on(...)` |
| A linear step has routes | `... cannot be converted to BranchingStep<O>` | Replace the routes with the target state |
| Routes use another enum | `Stock cannot be converted to Validation` | Use the constants of the enum the step returns |
| The same for an await event | the same symptoms with `message(...)`, `every(...)` | The same fixes |

Do not use raw types or casts on handlers: they turn these compile errors into runtime errors.

`build()` checks the rest: duplicate states and events, undeclared targets, incomplete routes, non-positive delays,
an await without an exit and unregistered workers.
