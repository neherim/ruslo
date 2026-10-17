# Error handling

English | [Русский](ru/error-handling.md)

An exception from a step, message handler or timer handler means a technical failure or a programming error, not a
regular business result. Express an expected business situation as an outcome of a branching handler.

The exception rolls back the handler's transaction: the state, variables and the failed event (a message or a due
timer) stay as they were. The engine then calls `ProcessErrorHandler` in a separate transaction, and it decides
what to do next.

## ProcessErrorHandler

```java
ErrorResult handle(ProcessContext context, ProcessError error);
```

`ProcessError` contains:

- `cause`: the handler's exception;
- `attempt`: the number of previous failed attempts in the current state (`0` for the first error).

Read the rest (process id, type, state, variables) from `ProcessContext`.

The handler returns one of three results:

- `ErrorResult.retryAfter(delay)`: retry the failed event after `delay`. New messages are accepted while the retry
  is pending, but are not handled before it.
- `ErrorResult.fail()`: stop the process in the current state with status `FAILED`. Continue with
  `resumeFailedProcess(id)`, which retries the failed event.
- `ErrorResult.fallback()`: go to the process fallback state (see below), or to `FAILED` if there is none.

## Fallback state

Error handling is declared at the process level:

```java
.onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(5)),
         CANCELLING)   // fallbackState: where to go when retries are exhausted
.initialState(CREATED)
// ...
.step(CANCELLING, cancelOrderStep, CANCELLED)
.terminal(CANCELLED)
```

Forms: `onError(handler)` without a fallback state, and `onError(handler, fallbackState)`.

`fallback()` makes an ordinary transition to `fallbackState` (leaving an await clears its message queue).
The process stays in `FAILED` if `fallbackState` is not declared or the error happened in `fallbackState` itself;
otherwise a failing compensation would loop. The engine does not detect **indirect loops**
(`CANCELLING → X`, an error in `X` → `CANCELLING`): watch the definition topology yourself.

To pass the reason for the transition, use `ProcessContext`: the handler writes a variable, the fallback state reads it.

## Built-in handlers

| Handler | Behavior |
|---|---|
| `ProcessErrorHandler.noRetry()` | Returns `fallback()` immediately |
| `ProcessErrorHandler.fixed(maxRetries, delay)` | Up to `maxRetries` retries with a fixed delay, then `fallback()` |
| `ProcessErrorHandler.exponential(maxRetries, initialDelay, maxDelay)` | The same with a doubling delay |

The default policy is `noRetry()`: without `fallbackState` the process goes to `FAILED` immediately.
**With a declared `fallbackState`, `noRetry()` moves the process to the fallback state right after the first error.**

A custom handler:

```java
(context, error) -> {
    if (error.cause() instanceof PermanentlyUnavailableException) return ErrorResult.fallback();
    if (error.cause() instanceof DataCorruptionException) return ErrorResult.fail();
    if (error.attempt() < 3) return ErrorResult.retryAfter(Duration.ofSeconds(10));
    return ErrorResult.fallback();
}
```

## Guarantees

- The failed handler's transaction is rolled back before the error handler is called; the error handler sees the
  last saved process state.
- `ProcessContext` changes made by the error handler are saved atomically with its result.
- With `retryAfter` and `fail`, the failed event is not considered handled. If the await deadline passes while the
  retry is pending, `timeout` fires (see [execution.md](execution.md#await-event-selection)).
- If the error handler itself throws or returns `null`, the failed event is retried after 60 seconds;
  `lastError` keeps the original error.
- A branching handler that returns `null` is treated as failed with `IllegalStateException` and goes through the
  same policy.
- A successful handler resets `attempt` and `lastError`.
- `lastError` stores `SimpleClassName: message` (`UnsupportedCountryException: Unsupported country: XX`);
  without a message, only the class name. The text is truncated to 1024 characters. The stack trace and causes
  (`getCause()`) are not stored: find them in the log by the process id.
- `resumeFailedProcess` applies only to `FAILED`. For an active or completed process it throws
  `ProcessStatusException` and changes nothing; the exception itself does not mark the caller's transaction
  rollback-only.
- A process in `FAILED` does not run: the dispatcher does not select it, timers do not fire, `sendMessageSync`
  throws `MessageNotExpectedException` with `getStatus() == FAILED` and emulator steps throw
  `ProcessStatusException`. `sendMessage` still accepts messages of the await: they stay in
  the queue and are handled after `resumeFailedProcess`.
- `resumeFailedProcess` resets `attempt`: the retry policy starts counting again after resume.
