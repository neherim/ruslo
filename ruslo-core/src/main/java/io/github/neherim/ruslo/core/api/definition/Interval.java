package io.github.neherim.ruslo.core.api.definition;

import java.time.Duration;

import static io.github.neherim.ruslo.core.utils.DateTimeUtils.ceilToMicros;
import static java.util.Objects.requireNonNull;

/**
 * Schedule of an {@code every} event: the first check {@code firstAfter} after entering the await, then every
 * {@code period}, see {@link ProcessSpecBuilder.AwaitBuilder#every(Interval, ProcessTimerHandler, ProcessState)}.
 * The period is rounded up to microseconds.
 */
public record Interval(Duration period, Duration firstAfter) {
    /**
     * @throws IllegalArgumentException if {@code period} is not positive or {@code firstAfter} is negative
     */
    public Interval {
        requireNonNull(period, "Period cannot be null");
        requireNonNull(firstAfter, "First check delay cannot be null");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("Period must be positive: " + period);
        }
        if (firstAfter.isNegative()) {
            throw new IllegalArgumentException("First check delay cannot be negative: " + firstAfter);
        }
        period = ceilToMicros(period);
    }

    /**
     * Returns an interval that first checks one {@code period} after entering the await.
     */
    public static Interval of(Duration period) {
        return new Interval(period, period);
    }

    /**
     * Returns a copy that first checks {@code delay} after entering the await; {@code ZERO} checks at once.
     */
    public Interval firstAfter(Duration delay) {
        return new Interval(period, delay);
    }
}
