package io.github.neherim.ruslo.core.api.definition;

import io.github.neherim.ruslo.core.utils.ExponentialBackoffProcessErrorHandler;

import java.time.Duration;

/**
 * Decides what happens after a step or event handler throws.
 */
@FunctionalInterface
public interface ProcessErrorHandler {

    /**
     * Returns the decision for the failed step or event. If this method throws or returns {@code null},
     * the engine retries the step or event after 60 seconds.
     */
    ErrorResult handle(ProcessContext context, ProcessError error);

    /**
     * Returns a handler that never retries: always {@link ErrorResult#fallback()}.
     */
    static ProcessErrorHandler noRetry() {
        return (context, error) -> ErrorResult.fallback();
    }

    /**
     * Returns a handler that retries up to {@code maxRetries} times with a fixed delay,
     * then returns {@link ErrorResult#fallback()}.
     */
    static ProcessErrorHandler fixed(int maxRetries, Duration delay) {
        return (context, error) -> {
            if (error.attempt() < maxRetries) {
                return ErrorResult.retryAfter(delay);
            }
            return ErrorResult.fallback();
        };
    }

    /**
     * Returns a handler that retries up to {@code maxRetries} times with a delay that starts at
     * {@code initialDelay} and doubles after each failure up to {@code maxDelay}, then returns
     * {@link ErrorResult#fallback()}.
     */
    static ProcessErrorHandler exponential(int maxRetries, Duration initialDelay, Duration maxDelay) {
        return new ExponentialBackoffProcessErrorHandler(maxRetries, initialDelay, maxDelay);
    }
}
