package io.github.neherim.ruslo.core.utils;

import java.sql.Timestamp;
import java.time.Instant;

public final class DateTimeUtils {
    private DateTimeUtils() {
    }

    public static Instant toInstant(Timestamp date) {
        return date == null ? null : date.toInstant();
    }
}
