package io.github.neherim.ruslo.core.utils;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessError;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ErrorResult;

import java.time.Duration;
import java.util.Objects;

/** Process error handler that retries the current step with exponential backoff. */
public class ExponentialBackoffProcessErrorHandler implements ProcessErrorHandler {
    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;

    public ExponentialBackoffProcessErrorHandler(int maxRetries, Duration initialDelay, Duration maxDelay) {
        Objects.requireNonNull(initialDelay, "Initial delay cannot be null");
        Objects.requireNonNull(maxDelay, "Max delay cannot be null");
        if (maxRetries < 0) {
            throw new IllegalArgumentException("Max retries cannot be negative");
        }
        if (initialDelay.isNegative()) {
            throw new IllegalArgumentException("Initial delay cannot be negative");
        }
        if (maxDelay.isNegative()) {
            throw new IllegalArgumentException("Max delay cannot be negative");
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("Max delay cannot be less than initial delay");
        }
        this.maxRetries = maxRetries;
        this.initialDelay = initialDelay;
        this.maxDelay = maxDelay;
    }

    @Override
    public ErrorResult handle(ProcessContext context, ProcessError error) {
        if (error.attempt() < maxRetries) {
            return ErrorResult.retryAfter(calculateDelay(error.attempt()));
        }
        return ErrorResult.fallback();
    }

    private Duration calculateDelay(int attempt) {
        var delay = initialDelay;
        for (var i = 0; i < attempt; i++) {
            if (delay.compareTo(maxDelay) >= 0) {
                return maxDelay;
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException ex) {
                return maxDelay;
            }
        }
        if (delay.compareTo(maxDelay) > 0) {
            return maxDelay;
        }
        return delay;
    }
}
