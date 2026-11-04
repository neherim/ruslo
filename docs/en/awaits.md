# Awaits

An await (`await`) stops the process until one of the declared events occurs: a message arrives or a timer fires. An
await can last minutes or months; while the process waits, it holds neither a thread nor a database connection.

## Await events

| Event | Fires |
|---|---|
| `message(type, handler, target)` | when a message of `type` arrives |
| `every(interval, handler, target)` | repeatedly, every `interval` |
| `after(delay[, handler], target)` | once, `delay` after entering the await |
| `at(timer[, handler], target)` | at the moment of the process timer `timer` |

`target` is the target state or routes on the handler's outcome (`routes -> routes.on(...)`), see
[transitions](processes.md#transitions). An await declares each timer kind at most once. It can declare any number of
reactions to messages.

```java
.await(WAITING_FOR_PAYMENT, await -> await
    .message(PAYMENT_CONFIRMED, PaymentDefinition::onPaid, SHIPPING)
    .every(Duration.ofDays(1), PaymentDefinition::remind, WAITING_FOR_PAYMENT)   // a daily reminder
    .at(PAYMENT_DUE, CANCELLED))                                               // a deadline set by the code
```

Event handlers:

| Event | Linear handler | Branching handler |
|---|---|---|
| `message` | `ProcessMessageHandler<T>` | `BranchingMessageHandler<T, O>` |
| `every`, `after`, `at` | `ProcessTimerHandler` | `BranchingTimerHandler<O>` |

For `after` and `at` the handler is optional: without it the event moves the process to `target` right away. Await
handlers always run on the transactional `DEFAULT` worker ([workers.md](workers.md)).

## Messages

A message type declares the name and the payload class:

```java
public static final MessageType<PaymentConfirmed> PAYMENT_CONFIRMED =
        MessageType.of("payment", PaymentConfirmed.class);

static void onPaid(ProcessContext context, IncomingMessage<PaymentConfirmed> message) {
    context.setVariable(PAYMENT_ID, message.payload().paymentId());
}
```

The handler receives an `IncomingMessage<T>` with the payload and the receive time. A message is sent by the process
id:

```java
processEngine.sendMessage(processId, PAYMENT_CONFIRMED, new PaymentConfirmed("txn-42"));
```

If only a domain key is known, first find the process by `businessKey`
([processes.md](processes.md#process-search)).

### Sending a message

- `sendMessage` accepts a message only if the process is now in an await that declares a handler for this message.
  Otherwise the method throws `MessageNotExpectedException` with the current process state and stores nothing. The
  sender retries `sendMessage` later or ignores the rejection. `MessageNotExpectedException` does not roll back the
  caller's transaction ([spring.md](spring.md#application-transactions)).
- A process in `FAILED` accepts messages of its await, but they are handled after
  `ProcessEngine.resumeFailedProcess`.

### What an accepted message guarantees

- Success means the message is stored in the process queue, **not** that its handler will be called. For example, if
  another event moves the process out of the await first, unhandled messages are removed (see
  [leaving the await](#staying-in-an-await-or-leaving-it)).
- Messages of one process are handled one at a time, in arrival order. The handler runs in one transaction with
  removing the message from the queue.
- **`sendMessage` is not idempotent**: sending the same payload again queues a second message.
- The engine does not keep message history: a message is removed after it is handled.

## Await timers: `every` and `after`

The engine sets the exact fire times of `every` and `after` when the process enters the await.

- `after(delay)` fires once, `delay` after entering.
- `every(period)` fires on a fixed grid from entering: `firstAfter + k * period` (`firstAfter` defaults to
  `period`). A late run of the `every` handler does not shift the grid, and points missed during downtime are not run
  in a batch: one check runs, then the grid continues. To move the first run of the handler, pass an `Interval`
  instead of a `Duration`. For example, to run `every` right on entering:
  `every(Interval.of(period).firstAfter(Duration.ZERO), handler, target)`.

## Process timers: `at`

`at(T)` waits for a named process timer (`TimerType`). Unlike `every` and `after`, the timer is controlled by user
code: it sets, moves and cancels it.

```java
static final TimerType PAYMENT_DUE = TimerType.of("paymentDue");

static void reserve(ProcessContext context) {                      // a step before the await
    context.setTimer(PAYMENT_DUE, context.now().plus(Duration.ofDays(5)));
}

static void extend(ProcessContext context, IncomingMessage<Instant> message) {   // targets the await itself
    context.setTimer(PAYMENT_DUE, message.payload());
}
```

A process timer consists of a name and a fire moment. The rules:

1. **A timer belongs to the process.** A process can have several timers with different names. A timer with one name
   is either not set or set to one fire moment; `setTimer` on a set timer replaces its fire moment.
2. **A timer is set at runtime.** At process start with `newProcess(...).timer(...)`, in handlers through the process
   context with `ProcessContext.setTimer` and `ProcessContext.cancelTimer`, manually through `ProcessEngineAdmin` or
   the Admin UI. A change takes effect at once, without entering the await again. The engine never sets a timer
   itself; it only cancels a timer after it fires (rule 5).
3. **A timer fires only in an await with `at`.** A timer fires when the process is in an await with `at` and the fire
   moment is not later than the engine time (`ProcessContext.now()`). If the fire moment has already passed when the
   process enters such an await, the timer fires at once. A timer that is not set does not fire.
4. **Transitions do not change the timer.** A timer stays set until it fires or is cancelled. This holds also when
   the process leaves an await with `at` by another event of that await (`message`, `after`, `every`): the timer then
   fires in the next await with `at` for the same name.
5. **A fired timer is cancelled after the `at` handler.** While the `at` handler runs, the timer stays set, and
   `ProcessContext.getTimer` returns its fire moment. After the handler the engine cancels the timer unless the
   handler set a new fire moment. If the handler fails, its changes are rolled back, and the retry sees the timer
   unchanged. For an `at` without a handler the timer is cancelled when it fires.

Moving the timer in the handler makes a schedule:

```java
.await(SCHEDULED, await -> await
    .at(NEXT_RUN, ReportDefinition::buildReport, routes -> routes
        .on(Run.MORE, SCHEDULED)
        .on(Run.LAST, DONE)))

static Run buildReport(ProcessContext context) {
    var runAt = context.getTimer(NEXT_RUN).orElseThrow();         // the fire moment
    // build the report for runAt
    context.setTimer(NEXT_RUN, runAt.plus(Duration.ofDays(1)));
    return Run.MORE;
}
```

Full examples: the `deadline` and `subscription` recipes in [`ruslo-examples/cookbook`](https://github.com/neherim/ruslo/tree/main/ruslo-examples/cookbook).

## Staying in an await or leaving it

An event whose target is the await itself keeps the process waiting: the handled message is removed, other queued
messages and the timers are kept ([transition to the same state](processes.md#transitions)).

A transition to another state closes the await, and **all its unhandled messages are removed**. To start an await
over, with an empty queue and new `every` and `after` timers, go through a step that leads back to it:

```java
.await(ACTIVE, await -> await
    .message(ACTIVITY, Session::onActivity, RECORDING_ACTIVITY)   // an ACTIVITY message restarts the timeout
    .after(Duration.ofMinutes(30), EXPIRED))
.step(RECORDING_ACTIVITY, Session::recordActivity, ACTIVE)
```

## Which event is handled

Events that occurred during an await are handled one by one, in the order of their time:

- `message`: the moment `sendMessage` stored it;
- `every`: its point of the schedule grid, counted from entering the `await`;
- `after`: the time of entering the `await` plus `delay`;
- `at`: the time set in the process timer.

An event is due when its time is not later than the engine clock. Among due events the one with the earliest time is
handled; on a tie the order is `message`, `every`, `after`, `at`. Several `message` events with the same send time are
handled in arrival order. When the dispatcher reached the process does not matter. A handled `message` is removed in
the handler's transaction. Timers never fire before their time, but may be a few seconds late; what the delay depends
on: [dispatcher.md](dispatcher.md#timer-accuracy).

## Errors in an await

A handler exception rolls back its transaction, and then the [error policy](error-handling.md) applies. In an await a
retry has a special effect: **an error pauses the whole await until the retry time**. New messages and due timers do not
wake the process. After the pause the earliest due event is handled, usually the failed one. So a temporary failure
delays handling but does not change the order of events.

The consequence: if a message accepted at 11:59 keeps failing, an `after` deadline at 12:00 waits for it; with
unlimited retries the deadline never fires. For a hard deadline:

- limit the retries (`ProcessErrorHandler.fixed(maxRetries, delay)` with `onGiveUp`);
- or catch the error in the handler and return an outcome that keeps the process waiting.

A process in `FAILED` handles no events. After `resumeFailedProcess` the rules of this page apply again.

## Synchronous message handling

`processEngine.sendMessageSync(processId, type, payload)` runs the message handler in the calling thread and
transaction. Use it when the caller needs the transition committed before the call returns, for example for a user
cancellation (the `cancellation` recipe) or to return the result in the response to an HTTP request.

- Only this message's handler runs synchronously. The dispatcher runs the following steps asynchronously after commit.
- The message bypasses the queue: earlier messages and due timers keep waiting.
- If the process does not expect this message now, the method throws `MessageNotExpectedException`; for a process in
  `FAILED` too, with `getStatus() == FAILED`. Nothing is stored.
- A handler exception propagates to the caller and rolls back the transaction: the message is not stored, and the
  error policy is not called.

## Testing

In tests timers come due only when the test moves the engine clock: [testing.md](testing.md#timers-and-retries).
