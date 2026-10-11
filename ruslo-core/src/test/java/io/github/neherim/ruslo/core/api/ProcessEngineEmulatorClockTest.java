package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessEngineEmulatorClockTest extends BaseTest {

    @Test
    void emulatorChangesMutableClock() {
        var clock = new MutableClock(ZoneOffset.UTC, Instant.parse("2026-10-07T10:00:00Z"), ChronoUnit.MICROS);
        try (var engine = newProcessEngine(clock)) {
            var emulator = new ProcessEngineEmulator(engine);

            emulator.fastForwardTime(Duration.ofMinutes(5));
            assertEquals(Instant.parse("2026-10-07T10:05:00Z"), clock.instant());

            emulator.setTime(Instant.parse("2026-10-08T00:00:00Z"));
            assertEquals(Instant.parse("2026-10-08T00:00:00Z"), clock.instant());
        }
    }

    @Test
    void emulatorResetsMutableClockToInitialTime() {
        var start = Instant.parse("2026-10-07T10:00:00Z");
        var clock = new MutableClock(ZoneOffset.UTC, start, ChronoUnit.MICROS);
        var zonedClock = clock.withZone(ZoneOffset.ofHours(3));
        try (var engine = newProcessEngine(clock)) {
            var emulator = new ProcessEngineEmulator(engine);

            emulator.fastForwardTime(Duration.ofHours(1));
            assertEquals(start.plus(Duration.ofHours(1)), zonedClock.instant());

            emulator.resetTime();
            assertEquals(start, clock.instant());
            assertEquals(start, zonedClock.instant());
        }
    }

    @Test
    void emulatorRejectsTimeChangeForNonMutableClock() {
        try (var engine = newProcessEngine(Clock.systemUTC())) {
            var emulator = new ProcessEngineEmulator(engine);

            assertThrows(IllegalStateException.class, () -> emulator.fastForwardTime(Duration.ofSeconds(1)));
            assertThrows(IllegalStateException.class, () -> emulator.setTime(Instant.EPOCH));
        }
    }

    private ProcessEngine newProcessEngine(Clock clock) {
        return new ProcessEngineBuilder(database)
            .withClock(clock)
            .withDispatcherEnabled(false)
            .withDatabaseMigrationEnabled(false)
            .build()
            .start();
    }
}
