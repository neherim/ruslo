package io.github.neherim.ruslo.core.api.definition;

import java.time.Duration;

import static java.util.Objects.requireNonNull;

/**
 * Schedule of an {@code every} event: the first check {@code firstAfter} entering the await, then every
 * {@code period}. The checks follow the grid described in
 * {@link ProcessSpecBuilder.AwaitBuilder#every(Interval, ProcessTimerHandler, ProcessState)}. Creating an interval
 * with a non-positive {@code period} or a negative {@code firstAfter} throws {@link IllegalArgumentException}.
 */
public record Interval(Duration period, Duration firstAfter) {
    public Interval {
        requireNonNull(period, "Period cannot be null");
        requireNonNull(firstAfter, "First check delay cannot be null");
        if (period.isZero() || period.isNegative()) {
            throw new IllegalArgumentException("Period must be positive: " + period);
        }
        if (firstAfter.isNegative()) {
            throw new IllegalArgumentException("First check delay cannot be negative: " + firstAfter);
        }
    }

    /** Returns an interval whose first check is one {@code period} after entering the await. */
    public static Interval of(Duration period) {
        return new Interval(period, period);
    }

    /** Returns a copy whose first check is {@code delay} after entering the await; {@code ZERO} checks at once. */
    public Interval firstAfter(Duration delay) {
        return new Interval(period, delay);
    }
}
