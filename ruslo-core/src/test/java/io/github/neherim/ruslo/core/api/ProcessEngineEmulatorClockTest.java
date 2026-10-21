package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

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

    @Test
    void runUntilStopWaitsForStepRetryDelay() {
        var clock = new MutableClock(ZoneOffset.UTC, Instant.parse("2026-10-07T10:00:00Z"), ChronoUnit.MICROS);
        var fail = new AtomicBoolean(true);
        ProcessDefinition definition = builder -> builder.type("STEP_RETRY")
            .onError(ProcessErrorHandler.fixed(1, Duration.ofMinutes(5)))
            .initialState(State.START)
            .step(State.START, context -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("boom");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngine(clock, definition)) {
            var emulator = new ProcessEngineEmulator(engine);
            var id = engine.newProcess(ProcessType.of("STEP_RETRY")).start();

            assertEquals(1, emulator.runUntilStop(id).attempt());
            assertEquals(1, emulator.runUntilStop(id).attempt());

            emulator.fastForwardTime(Duration.ofMinutes(5));
            assertEquals(ProcessStatus.COMPLETED, emulator.runUntilStop(id).status());
        }
    }

    private ProcessEngine newProcessEngine(Clock clock, ProcessDefinition... definitions) {
        return new ProcessEngineBuilder(database)
            .addProcessDefinitions(List.of(definitions))
            .withClock(clock)
            .withDispatcherEnabled(false)
            .withDatabaseMigrationEnabled(false)
            .build()
            .start();
    }

    private enum State implements ProcessState {
        START,
        DONE
    }
}
