package io.github.neherim.ruslo.core.internal;

import io.github.neherim.ruslo.core.api.definition.ErrorResult;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.test.TestProcessContext;
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
    void exponentialGivesUpWhenRetriesAreExhausted() {
        var handler = ProcessErrorHandler.exponential(
                3, Duration.ofSeconds(1), Duration.ofMinutes(1));

        var result = handler.handle(context(3), new RuntimeException("test"));

        assertInstanceOf(ErrorResult.GiveUp.class, result);
    }

    @Test
    void fixedRetriesThenGivesUp() {
        var handler = ProcessErrorHandler.fixed(2, Duration.ofSeconds(1));

        assertEquals(Duration.ofSeconds(1), assertInstanceOf(ErrorResult.RetryAfter.class,
                handler.handle(context(1), new RuntimeException("test")))
            .delay());
        assertInstanceOf(ErrorResult.GiveUp.class, handler.handle(context(2), new RuntimeException("test")));
    }

    @Test
    void noRetryGivesUpImmediately() {
        assertInstanceOf(ErrorResult.GiveUp.class,
                ProcessErrorHandler.noRetry().handle(context(0), new RuntimeException("test")));
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

        var result = handler.handle(context(attempt), new RuntimeException("test"));

        return assertInstanceOf(ErrorResult.RetryAfter.class, result).delay();
    }

    private ProcessContext context(int attempt) {
        return TestProcessContext.builder().attempt(attempt).build();
    }
}
