package io.github.neherim.ruslo.admin.dto;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Reads {@code datetime-local} form values, which carry no zone, in the browser time zone. */
public final class HtmlDateTime {
    private HtmlDateTime() {
    }

    /** Returns the browser time zone, or the server zone if {@code timeZone} is absent. */
    public static ZoneId zone(String timeZone) {
        return timeZone != null && !timeZone.isBlank() ? ZoneId.of(timeZone) : ZoneId.systemDefault();
    }

    /** Parses a {@code datetime-local} value in {@code zone}; a value ending with {@code Z} is read as UTC. */
    public static Instant parse(String value, ZoneId zone) {
        if (value.endsWith("Z")) {
            return Instant.parse(value);
        }
        return LocalDateTime.parse(value).atZone(zone).toInstant();
    }
}
