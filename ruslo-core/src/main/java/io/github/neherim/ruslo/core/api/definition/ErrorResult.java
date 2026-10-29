package io.github.neherim.ruslo.core.api.definition;

import java.time.Duration;
import java.util.Objects;

/**
 * Decision of a {@link ProcessErrorHandler}.
 */
public sealed interface ErrorResult permits ErrorResult.RetryAfter, ErrorResult.Fail, ErrorResult.GiveUp {
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
     * Gives up and lets the definition decide: moves to the state declared with
     * {@link ProcessSpecBuilder.TypedBuilder#onGiveUp(ProcessState) onGiveUp}, or stops in {@code FAILED} like
     * {@link #fail()} if none is declared or the process is already in it.
     */
    static ErrorResult giveUp() {
        return new GiveUp();
    }

    record RetryAfter(Duration delay) implements ErrorResult {
        public RetryAfter {
            Objects.requireNonNull(delay, "Delay cannot be null");
            if (delay.isNegative()) throw new IllegalArgumentException("Retry delay cannot be negative");
        }
    }

    record Fail() implements ErrorResult {
    }

    record GiveUp() implements ErrorResult {
    }
}
