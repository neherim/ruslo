# Error handling

An exception from a step, message handler or timer handler means a technical failure or a programming error. Express
an expected business situation (a declined card, a rejected application) as an outcome of a branching handler.

The exception rolls back the handler's transaction: the state, the variables and the event being handled stay as
they were. Then the engine calls the process's `ProcessErrorHandler` in a separate transaction, and its result
decides what happens next.

## Error policy

```java
.onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(5)))
.onGiveUp(CANCELLING)
.initialState(CREATED)
// ...
.step(CANCELLING, cancelOrderStep, CANCELLED)
.terminal(CANCELLED)
```

Both are optional: `onError` sets the handler (default `noRetry()`), `onGiveUp` sets the state to go to when the
handler gives up.

The handler returns one of three results:

| Result | What happens |
|---|---|
| `ErrorResult.retryAfter(delay)` | The step or the await runs again after `delay` |
| `ErrorResult.giveUp()` | The process goes to the `onGiveUp` state; without `onGiveUp` it stops in `FAILED` |
| `ErrorResult.fail()` | The process stops in `FAILED` in the current state, whether `onGiveUp` is declared or not |

Built-in handlers:

| Handler | Behavior |
|---|---|
| `ProcessErrorHandler.noRetry()` | `giveUp()` on the first error |
| `ProcessErrorHandler.fixed(maxRetries, delay)` | Up to `maxRetries` retries with a fixed delay, then `giveUp()` |
| `ProcessErrorHandler.exponential(maxRetries, initialDelay, maxDelay)` | The same with a doubling delay |

A custom handler receives the `ProcessContext` and a `ProcessError` with the exception (`cause`) and the number of
previous failed attempts in the current state (`attempt`, `0` for the first error):

```java
(context, error) -> {
    if (error.cause() instanceof PermanentlyUnavailableException) return ErrorResult.giveUp();
    if (error.cause() instanceof DataCorruptionException) return ErrorResult.fail();
    if (error.attempt() < 3) return ErrorResult.retryAfter(Duration.ofSeconds(10));
    return ErrorResult.giveUp();
}
```

## Give-up state

`giveUp()` is an ordinary transition to the `onGiveUp` state, for example a compensation step. To pass the reason,
the error handler writes a variable to the `ProcessContext`, and the give-up state reads it.

An error in the `onGiveUp` state itself stops the process in `FAILED`, so a failing compensation does not loop.
The engine does not detect indirect loops (`CANCELLING → X`, an error in `X` → `CANCELLING`).

## Retries in an await

A retry pauses the whole await: until the retry, new messages and due timers are not handled. If an await has a hard
deadline, limit the retries or catch the error in the handler. Details:
[execution.md](execution.md#retries-pause-the-await).

## FAILED processes

A process in `FAILED` does not run: no steps, no timers. Find such processes by status
([operations.md](operations.md#process-search)), fix the cause and continue with
`processEngine.resumeFailedProcess(id)`: the failed step or event runs again, and the attempt count starts over.
For a process that is not in `FAILED`, `resumeFailedProcess` throws `ProcessStatusException`.

## Details

- `ProcessContext` changes made by the error handler are saved atomically with its result.
- If the error handler itself throws or returns `null`, the failed event is retried after 60 seconds.
- A branching handler that returns `null` fails with `IllegalStateException`, handled by the same policy.
- A successful handler resets `attempt` and `lastError`.
- `lastError` stores `SimpleClassName: message`, truncated to 1024 characters. The stack trace is not stored: find it
  in the log by the process id.
