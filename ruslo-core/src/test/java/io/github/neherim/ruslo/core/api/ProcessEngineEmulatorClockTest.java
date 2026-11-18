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
import java.util.concurrent.atomic.AtomicBoolean;

import static java.time.temporal.ChronoUnit.NANOS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessEngineEmulatorClockTest extends BaseTest {

    @Test
    void emulatorResetsMutableClockAndItsZonedView() {
        var start = Instant.parse("2026-10-07T10:00:00.000000600Z");
        var clock = new MutableClock(ZoneOffset.UTC, start, NANOS);
        var zone = ZoneOffset.ofHours(3);
        var zonedClock = clock.withZone(zone);

        try (var engine = newProcessEngineWith(clock)) {
            var emulator = new ProcessEngineEmulator(engine);
            emulator.fastForwardTime(Duration.ofHours(1));
            assertEquals(zone, zonedClock.getZone());
            assertEquals(start.plus(Duration.ofHours(1)), zonedClock.instant());

            emulator.resetTime();
            assertEquals(start, clock.instant());
            assertEquals(start, zonedClock.instant());
        }
    }

    @Test
    void emulatorRejectsTimeChangesAndIgnoresResetWithFixedClock() {
        try (var engine = newProcessEngineWith(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))) {
            var emulator = new ProcessEngineEmulator(engine);

            assertThrows(IllegalStateException.class, () -> emulator.fastForwardTime(Duration.ofSeconds(1)));
            assertThrows(IllegalStateException.class, () -> emulator.setTime(Instant.EPOCH.plusSeconds(1)));
            assertDoesNotThrow(emulator::resetTime);
        }
    }

    @Test
    void contextNowUsesMicrosecondsFromUpdatedMutableClock() {
        var start = Instant.parse("2026-10-07T10:00:00.000000600Z");
        var clock = new MutableClock(ZoneOffset.UTC, start, NANOS);
        var seen = VariableType.of("seen", Instant.class);
        ProcessDefinition definition = builder -> builder.type("NOW")
            .initialState(State.START)
            .step(State.START, context -> context.setVariable(seen, context.now()), State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(clock, definition)) {
            var emulator = new ProcessEngineEmulator(engine);
            var id = engine.newProcess(ProcessType.of("NOW")).start();
            var advance = Duration.ofHours(3);
            emulator.fastForwardTime(advance);
            assertEquals(start.plus(advance), clock.instant());

            var done = emulator.runUntilStop(id);

            assertEquals(Instant.parse("2026-10-07T13:00:00Z"), done.variables().find(seen).orElseThrow());
        }
    }

    @Test
    void runUntilStopWaitsForStepRetryDelay() {
        var start = Instant.parse("2026-10-07T10:00:00.000000600Z");
        var clock = new MutableClock(ZoneOffset.UTC, start, NANOS);
        var retryDelay = Duration.ofMinutes(5).plusNanos(100);
        var fail = new AtomicBoolean(true);
        ProcessDefinition definition = builder -> builder.type("STEP_RETRY")
            .onError(ProcessErrorHandler.fixed(1, retryDelay))
            .initialState(State.START)
            .step(State.START, context -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("boom");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(clock, definition)) {
            var emulator = new ProcessEngineEmulator(engine);
            var id = engine.newProcess(ProcessType.of("STEP_RETRY")).start();

            var failed = emulator.runUntilStop(id);
            assertEquals(1, failed.attempt());
            var deadline = Instant.parse("2026-10-07T10:05:00.000001Z");
            assertEquals(deadline, failed.nextRunAt().orElseThrow());

            emulator.setTime(deadline.minusNanos(1));
            assertEquals(deadline.minusNanos(1), clock.instant());
            assertEquals(1, emulator.runUntilStop(id).attempt());
            emulator.setTime(deadline);
            assertEquals(ProcessStatus.COMPLETED, emulator.runUntilStop(id).status());
        }
    }

    private enum State implements ProcessState {
        START,
        DONE
    }
}
