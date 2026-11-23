package io.github.neherim.ruslo.core.api;

import java.time.Instant;

/**
 * Supplies the current time for a process engine. Implementations must be thread-safe.
 */
@FunctionalInterface
public interface RusloTimeSource {
    /** Returns the current instant, never {@code null}. */
    Instant now();
}
