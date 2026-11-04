# Steps

A step (`step`) runs the handler code and moves on right away to the next state declared in the `ProcessDefinition`.

## Linear step

A linear step implements `ProcessStep`. After `ProcessStep.execute()` returns, the engine moves the process to the
next state:

```java
.step(CREATED, OrderDefinition::reserveItems, WAITING_FOR_PAYMENT)

static void reserveItems(ProcessContext context) {
    var order = context.getVariable(ORDER).orElseThrow();
    // reserve the order items
}
```

## Branching step

A branching step implements `BranchingStep<O>` and returns a constant of enum `O`, and the definition maps each
constant to a state:

```java
enum Validation { VALID, REJECTED }

.step(VALIDATING, validateStep, routes -> routes
    .on(Validation.VALID, RESERVING)
    .on(Validation.REJECTED, CANCELLED))
```

The compiler checks that the routes use the step's enum, and `build()` checks that every constant has a route.
Express expected business situations as outcomes (an application is rejected, a card is declined). The engine treats
an exception from the handler as a technical error, see [error-handling.md](error-handling.md).

A step whose target is the step itself runs again ([transition to the same state](processes.md#transitions)).

## Step worker

A step runs on the process worker (`defaultWorker(...)`, `DEFAULT` by default) or on the worker passed right after
the state:

```java
.step(EXPORTING, IO_WORKER, exportDocumentStep, COMPLETED)
```

The worker mode determines the transaction the step runs in:

- on a transactional worker (`DEFAULT`) the step code and saving the transition run in one transaction: application
  database changes are committed exactly once, together with the transition;
- on a non-transactional worker the step code runs outside the engine transaction and does not hold a database
  connection; application database changes are repeated when the step is retried.

Keep steps that work with the database on `DEFAULT`. Move a long external call to a separate step on a
non-transactional worker. Modes, registration and choosing `max-concurrency`: [workers.md](workers.md).

## Repeated execution

An external call from a step runs **at least once** in any worker mode. If the node fails or the transaction rolls
back after an HTTP request or a Kafka send, the step runs again. So:

- make external calls idempotent, e.g. pass an idempotency key from the process variables;
- do not rely on a step running exactly once, even if it did not fail.

A retry after an exception is set by the [error policy](error-handling.md); the attempt number is available through
`ProcessContext.attempt()`. A step can also run again without an exception: if the node failed or hung in the middle
of the step ([dispatcher.md](dispatcher.md#node-failure)).
