package io.github.neherim.ruslo.core.utils;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

public final class DateTimeUtils {
    private DateTimeUtils() {
    }

    public static Instant toInstant(Timestamp date) {
        return date == null ? null : date.toInstant();
    }

    /** Rounds the moment up to the timestamp precision shared by the supported databases. */
    public static Instant ceilToMicros(Instant moment) {
        var remainder = moment.getNano() % 1000;
        return remainder == 0 ? moment : moment.plusNanos(1000 - remainder);
    }

    /** Rounds the duration up to microseconds, preserving zero and positive periods. */
    public static Duration ceilToMicros(Duration duration) {
        var remainder = duration.getNano() % 1000;
        return remainder == 0 ? duration : duration.plusNanos(1000 - remainder);
    }
}
