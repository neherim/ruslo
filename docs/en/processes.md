# Processes

This page describes a process as a whole: how to declare and start it, what data it keeps and how it lives.

## Process definition

A process definition (`ProcessDefinition`) is a set of transitions between states (`ProcessState`) of three kinds:

- a **step** (`step`) runs code and always moves on: [steps.md](steps.md);
- an **await** (`await`) waits for a message or a timer: [awaits.md](awaits.md);
- a **terminal** state (`terminal`) completes the process.

At any moment a process is in exactly one state: there are no parallel branches inside a process. Split parallel
work into separate processes.

The whole transition graph is visible in one place:

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
```

The process type (`ProcessType`) identifies the definition: it is used to start processes, search and test them. The
initial state (`initialState`) is a step or an await. Before the list of states, the definition sets the settings of
the whole process: the default worker (`defaultWorker`, see [workers.md](workers.md)) and the error policy
(`onError`, `onGiveUp`, see [error-handling.md](error-handling.md)).

The engine returns the declared states and types, so compare them with the constants:
`details.state() == State.DONE`. A state the definition no longer declares is returned as `ProcessState.of(name)`.

`build()` validates the definition and throws `IllegalArgumentException` if it is inconsistent: for example, an
outcome constant has no route or a step refers to an unregistered worker.

In Spring Boot a definition is a bean registered on startup: [spring.md](spring.md#definitions).

## Transitions

The target of a transition is set in one of two ways:

- by a state: `step(CREATED, handler, WAITING)`;
- by routes on the handler's outcome: `routes -> routes.on(Outcome.OK, NEXT).on(Outcome.FAILED, CANCELLED)`.
  The handler returns an enum constant, and the definition maps each constant to a state. Both steps and await events
  branch this way.

The handler reports a result, and the definition decides where to go. That is why the whole graph is visible in
`define`.

Every handler call ends with one transaction that saves the new state and the `ProcessContext` changes. The next
handler runs in a new transaction. If the next state runs on the same worker, the same node continues the process
right away; otherwise the process returns to the shared queue. A handler exception rolls back its transaction: the
state and the variables stay as they were, and the [error policy](error-handling.md) applies.

**A transition to the same state is not a state change.** The message queue, the `every` and `after` timers and the
history are kept, the error is cleared. A step runs again, an await keeps waiting. Loops are built this way: a step
that processes data in chunks, an await that handles messages one after another.

## Starting a process

```java
UUID processId = processEngine.newProcess(OrderDefinition.TYPE)
        .businessKey("order-42")
        .variable(ORDER, order)
        .timer(PAYMENT_DUE, deadline)
        .start();
```

| Method | Purpose |
|---|---|
| `businessKey(String)` | A key from the domain, e.g. the order number. The process is found by it (`byBusinessKey`) when an external event arrives. The engine does not check its uniqueness |
| `correlationId(String)` | An id that links logs. Without it, the current one is taken from the MDC or a new one is created |
| `variable(type, value)` | An initial process variable |
| `timer(timer, moment)` | An initial process timer for `at`, see [awaits.md](awaits.md#process-timers-at) |

`start()` saves the process and returns its id.

## Process context

A handler receives a `ProcessContext`:

| Method | Purpose |
|---|---|
| `getVariable`, `setVariable`, `removeVariable` | Process variables |
| `getTimer`, `setTimer`, `cancelTimer` | Process timers for `at`: [awaits.md](awaits.md#process-timers-at) |
| `processId()`, `processType()`, `state()`, `businessKey()`, `correlationId()` | Process identity |
| `attempt()` | Number of failed attempts of the current step or event: [error-handling.md](error-handling.md) |
| `now()` | Engine time; use it instead of `Instant.now()`, so that tests control it |

Context changes are saved together with the transition of the process between states.

Variables and message payloads are stored as JSON using ruslo's built-in Jackson configuration, shared by the engine
and `TestProcessContext`. Use JSON-compatible DTOs, preferably records. Their class must be concrete: wrap a `List`
or `Map` in a record (`record OrderItems(List<String> itemIds) {}`).

## Status

| Status | When |
|---|---|
| `ACTIVE` | The process is started and is running or waiting |
| `COMPLETED` | The process has moved to a terminal state |
| `FAILED` | The error policy stopped the process; it does not run until it is continued manually |

A handler exception is a technical error. Express expected business situations as outcomes of a branching handler.
How a process gets to `FAILED` and how to continue it: [error-handling.md](error-handling.md).

## Process search

`processEngine.processes()` returns a query; filters are combined with AND:

```java
List<ProcessInstanceSummary> failed = processEngine.processes()
        .byType(OrderDefinition.TYPE)
        .byStatus(ProcessStatus.FAILED)
        .list(100, 0);

Optional<ProcessInstanceDetails> order = processEngine.processes()
        .byBusinessKey("order-42")
        .singleDetails();
```

Filters: id, type, state, status, business key, correlation id, ranges of `createdAt` and `nextRunAt`, processes with
failed attempts (`byAttempted`) and a `LIKE` pattern on variables. `list` returns summaries, `single` and
`singleDetails` return at most one process and throw `TooManyRowsException` if several match.

`ProcessInstanceDetails` adds the variables, process timers, last error and state history to the summary. Variables
are read in a typed way: `details.variables().get(ORDER_ID)` returns an `Optional`.

The query runs in the caller's transaction. `withLock()` locks the found rows with `SELECT ... FOR UPDATE` until the
transaction ends.

## Definition evolution

The engine stores the type and state names, but not the definition version. Running processes use the definition of
the current application. Keep old states while processes are in them. If a state was removed anyway, move the
affected processes.
