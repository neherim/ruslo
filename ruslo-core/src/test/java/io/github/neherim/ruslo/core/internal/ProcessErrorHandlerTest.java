package io.github.neherim.ruslo.core.internal;

import io.github.neherim.ruslo.core.api.definition.ProcessError;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ErrorResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessErrorHandlerTest {

    @Test
    void exponentialDoublesDelayForEachAttempt() {
        assertEquals(Duration.ofSeconds(1), retryDelay(0, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertEquals(Duration.ofSeconds(2), retryDelay(1, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertEquals(Duration.ofSeconds(4), retryDelay(2, Duration.ofSeconds(1), Duration.ofMinutes(1)));
    }

    @Test
    void exponentialCapsDelayAtMaxDelay() {
        assertEquals(Duration.ofSeconds(5), retryDelay(3, Duration.ofSeconds(1), Duration.ofSeconds(5)));
    }

    @Test
    void exponentialFallsBackWhenRetriesAreExhausted() {
        var handler = ProcessErrorHandler.exponential(
            3, Duration.ofSeconds(1), Duration.ofMinutes(1));

        var result = handler.handle(null, error(3));

        assertInstanceOf(ErrorResult.Fallback.class, result);
    }

    @Test
    void fixedRetriesThenFallsBack() {
        var handler = ProcessErrorHandler.fixed(2, Duration.ofSeconds(1));

        assertEquals(Duration.ofSeconds(1), assertInstanceOf(ErrorResult.RetryAfter.class,
            handler.handle(null, error(1))).delay());
        assertInstanceOf(ErrorResult.Fallback.class, handler.handle(null, error(2)));
    }

    @Test
    void noRetryFallsBackImmediately() {
        assertInstanceOf(ErrorResult.Fallback.class, ProcessErrorHandler.noRetry().handle(null, error(0)));
    }

    @Test
    void exponentialRejectsInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> ProcessErrorHandler.exponential(
            -1, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertThrows(NullPointerException.class, () -> ProcessErrorHandler.exponential(
            3, null, Duration.ofMinutes(1)));
        assertThrows(NullPointerException.class, () -> ProcessErrorHandler.exponential(
            3, Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class, () -> ProcessErrorHandler.exponential(
            3, Duration.ofSeconds(-1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> ProcessErrorHandler.exponential(
            3, Duration.ofSeconds(1), Duration.ofSeconds(-1)));
        assertThrows(IllegalArgumentException.class, () -> ProcessErrorHandler.exponential(
            3, Duration.ofSeconds(2), Duration.ofSeconds(1)));
    }

    private Duration retryDelay(int attempt, Duration initialDelay, Duration maxDelay) {
        var handler = ProcessErrorHandler.exponential(10, initialDelay, maxDelay);

        var result = handler.handle(null, error(attempt));

        return assertInstanceOf(ErrorResult.RetryAfter.class, result).delay();
    }

    private ProcessError error(int attempt) {
        return new ProcessError(new RuntimeException("test"), attempt);
    }
}
