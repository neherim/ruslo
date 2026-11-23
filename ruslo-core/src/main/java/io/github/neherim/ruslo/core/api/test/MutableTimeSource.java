package io.github.neherim.ruslo.core.api.test;

import io.github.neherim.ruslo.core.api.RusloTimeSource;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Provides a fixed time for deterministic tests until explicitly changed. Updates are thread-safe.
 */
public final class MutableTimeSource implements RusloTimeSource {
    private final Instant initialTime;
    private final AtomicReference<Instant> time;

    /** Creates a source initially set to the current system time. */
    public MutableTimeSource() {
        this(Instant.now());
    }

    /** Creates a source initially set to the given instant. */
    public MutableTimeSource(Instant initialTime) {
        this.initialTime = Objects.requireNonNull(initialTime, "Initial time must not be null");
        this.time = new AtomicReference<>(initialTime);
    }

    @Override
    public Instant now() {
        return time.get();
    }

    /** Sets the current time to the given instant. */
    public void setTime(Instant time) {
        this.time.set(Objects.requireNonNull(time, "Time must not be null"));
    }

    /** Adds the given duration to the current time. */
    public void fastForwardTime(Duration duration) {
        time.updateAndGet(current -> current.plus(duration));
    }

    /** Restores the initial time. */
    public void reset() {
        time.set(initialTime);
    }
}
