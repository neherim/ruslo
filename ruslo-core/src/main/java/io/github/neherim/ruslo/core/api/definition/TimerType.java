package io.github.neherim.ruslo.core.api.definition;

/**
 * Named process timer: a moment that handlers set and cancel and that an {@code at} event of an await waits for.
 * Unlike {@code every} and {@code after}, it is not tied to one entry of an await; see
 * {@link ProcessContext#setTimer(TimerType, java.time.Instant)}.
 *
 * @param name timer name
 */
public record TimerType(String name) {

    /**
     * @throws IllegalArgumentException if the name is blank
     */
    public TimerType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Timer name cannot be blank");
        }
    }

    public static TimerType of(String name) {
        return new TimerType(name);
    }
}
