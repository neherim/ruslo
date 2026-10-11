package io.github.neherim.ruslo.core.api.definition;

import java.time.Duration;
import java.util.Objects;

/**
 * Decision of a {@link ProcessErrorHandler}.
 */
public sealed interface ErrorResult permits ErrorResult.RetryAfter, ErrorResult.Fail, ErrorResult.Fallback {
    /**
     * Retries the failed step or event after the delay.
     */
    static ErrorResult retryAfter(Duration delay) {
        return new RetryAfter(delay);
    }

    /**
     * Stops the process in {@code FAILED} in its current state until it is resumed manually.
     */
    static ErrorResult fail() {
        return new Fail();
    }

    /**
     * Gives up and lets the definition decide: moves to {@code fallbackState}, or to {@code FAILED} if none is
     * declared or the process is already in it.
     */
    static ErrorResult fallback() {
        return new Fallback();
    }

    record RetryAfter(Duration delay) implements ErrorResult {
        public RetryAfter {
            Objects.requireNonNull(delay, "Delay cannot be null");
            if (delay.isNegative()) throw new IllegalArgumentException("Retry delay cannot be negative");
        }
    }

    record Fail() implements ErrorResult {
    }

    record Fallback() implements ErrorResult {
    }
}
