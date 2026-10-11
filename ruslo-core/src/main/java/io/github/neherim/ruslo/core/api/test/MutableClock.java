package io.github.neherim.ruslo.core.api.test;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Clock that stands still until changed, for deterministic tests. Clocks from {@link #withZone} share its time.
 */
public class MutableClock extends Clock {
    private final ZoneId zoneId;
    private final ChronoUnit precision;
    private final Instant initialTime;
    private final AtomicReference<Instant> time;

    public MutableClock() {
        this(ZoneId.systemDefault(), Instant.now(), ChronoUnit.MICROS);
    }

    /**
     * @param precision unit {@link #instant()} is truncated to
     */
    public MutableClock(ZoneId zoneId, Instant initTime, ChronoUnit precision) {
        this(zoneId, precision, initTime, new AtomicReference<>(initTime));
    }

    private MutableClock(ZoneId zoneId, ChronoUnit precision, Instant initialTime, AtomicReference<Instant> time) {
        this.zoneId = zoneId;
        this.precision = precision;
        this.initialTime = initialTime;
        this.time = time;
    }

    @Override
    public ZoneId getZone() {
        return zoneId;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new MutableClock(zone, precision, initialTime, time);
    }

    @Override
    public Instant instant() {
        return time.get().truncatedTo(precision);
    }

    public void setTime(Instant time) {
        this.time.set(time);
    }

    public void setTime(LocalDateTime time) {
        this.time.set(ZonedDateTime.of(time, zoneId).toInstant());
    }

    public void fastForwardTime(Duration duration) {
        this.time.updateAndGet(current -> current.plus(duration));
    }

    /** Resets to the initial time. */
    public void reset() {
        this.time.set(initialTime);
    }
}
