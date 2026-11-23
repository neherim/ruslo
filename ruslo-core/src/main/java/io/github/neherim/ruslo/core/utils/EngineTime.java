package io.github.neherim.ruslo.core.utils;

import io.github.neherim.ruslo.core.api.RusloTimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static java.time.temporal.ChronoUnit.MICROS;

/**
 * Aligns process scheduling with the microsecond precision of database timestamps.
 * Time readings and absolute timer moments are truncated so work scheduled for now is ready after a database
 * round trip. Relative delays are rounded up so a positive delay always advances the schedule.
 */
public final class EngineTime {
    private final RusloTimeSource source;

    public EngineTime(RusloTimeSource source) {
        this.source = Objects.requireNonNull(source, "Time source must not be null");
    }

    /** Returns the current process time at microsecond precision. */
    public Instant now() {
        return truncate(source.now());
    }

    /** Returns the original time source so the emulator can control a mutable source. */
    public RusloTimeSource source() {
        return source;
    }

    /** Truncates a timestamp to the engine's precision. */
    public static Instant truncate(Instant instant) {
        return instant.truncatedTo(MICROS);
    }

    /** Rounds a nonnegative delay to the next clock tick when needed. */
    public static Duration roundDelayUp(Duration delay) {
        var truncated = delay.truncatedTo(MICROS);
        return delay.equals(truncated) ? delay : truncated.plus(1, MICROS);
    }
}
