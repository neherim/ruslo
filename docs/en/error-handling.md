# Error handling

An exception from a step, message handler or timer handler means a technical failure or a programming error. Express
an expected business situation (a declined card, a rejected application) as an outcome of a branching handler
`BranchingStep`.

The exception rolls back the handler's transaction: the state, the variables and the event being handled stay as
they were. Then the engine calls the process's `ProcessErrorHandler` in a separate transaction, and its result
decides what happens next.

## Error policy

```java
@Override
public ProcessSpec define(ProcessSpecBuilder builder) {
    return builder
            .type(TYPE)
            // Retry a failed step 5 times with an exponential delay
            .onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(5)))
            // If the failed step still has not succeeded after 5 attempts, move the process to CANCELLED
            .onGiveUp(CANCELLED)
            .initialState(CREATED)
            // ...
            .terminal(CANCELLED)
            .build();
}
```

Both calls are optional: `onError` sets the handler (default `noRetry()`), `onGiveUp` sets the state the process
moves to when the handler gives up.

The handler implements `ProcessErrorHandler`; its `handle` method returns one of three results:

| Result | What happens |
|---|---|
| `ErrorResult.retryAfter(delay)` | The handler runs again after `delay` |
| `ErrorResult.giveUp()` | The process moves to the `onGiveUp` state. If the process declares no `onGiveUp`, it stops in `FAILED` |
| `ErrorResult.fail()` | The process stops with status `FAILED` in the current state |

Built-in handlers:

| Handler | Behavior |
|---|---|
| `ProcessErrorHandler.noRetry()` | `giveUp()` on the first error |
| `ProcessErrorHandler.fixed(maxRetries, delay)` | Up to `maxRetries` retries with a fixed delay, then `giveUp()` |
| `ProcessErrorHandler.exponential(maxRetries, initialDelay, maxDelay)` | The same with a doubling delay |

An example of a custom error handler:

```java
public class CustomProcessErrorHandler implements ProcessErrorHandler {
    public ErrorResult handle(ProcessContext context, Throwable cause) {
        if (cause instanceof PermanentlyUnavailableException) return ErrorResult.giveUp();
        if (cause instanceof DataCorruptionException) return ErrorResult.fail();
        if (context.attempt() < 3) return ErrorResult.retryAfter(Duration.ofSeconds(10));
        return ErrorResult.giveUp();
    }
}
```

## Give-up state

`giveUp()` is an ordinary transition to the state declared in `onGiveUp`, for example a compensation step. To pass the
cause of the error, write it to a `ProcessContext` variable.
An error in the `onGiveUp` state itself stops the process in `FAILED`, so a failing compensation does not loop.

## Retries in an await

A retry pauses the whole await (`await`): until the retry, new messages and due timers are not handled. What this
means for deadlines and how to deal with it: [awaits.md](awaits.md#errors-in-an-await).

## FAILED processes

A process in `FAILED` does not run: no steps, no timers. Find such processes by status
([processes.md](processes.md#process-search)), fix the cause and continue with
`processEngine.resumeFailedProcess(id)`: the failed step or event runs again, and the attempt count starts over.
For a process that is not in `FAILED`, `resumeFailedProcess` throws `ProcessStatusException`.

## Details

- `ProcessContext` changes made by the error handler are saved atomically with its result.
- If the error handler itself throws or returns `null`, the failed event is retried after 60 seconds.
- A branching handler that returns `null` fails with `IllegalStateException`, handled by the same policy.
- A successful handler resets `attempt` and `lastError`.
- `lastError` stores `SimpleClassName: message`, truncated to 1024 characters. The stack trace is not stored: find it
  in the log by the process id.
