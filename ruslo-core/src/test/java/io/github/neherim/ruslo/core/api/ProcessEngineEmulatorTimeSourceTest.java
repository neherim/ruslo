package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableTimeSource;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessEngineEmulatorTimeSourceTest extends BaseTest {

    @Test
    void emulatorResetsMutableTimeSource() {
        var start = Instant.parse("2026-10-07T10:00:00.000000600Z");
        var source = new MutableTimeSource(start);

        try (var engine = newProcessEngineWith(source)) {
            var emulator = new ProcessEngineEmulator(engine);
            emulator.fastForwardTime(Duration.ofHours(1));
            assertEquals(start.plus(Duration.ofHours(1)), source.now());

            emulator.resetTime();
            assertEquals(start, source.now());
        }
    }

    @Test
    void emulatorRejectsTimeChangesAndIgnoresResetWithFixedTimeSource() {
        try (var engine = newProcessEngineWith(() -> Instant.EPOCH)) {
            var emulator = new ProcessEngineEmulator(engine);

            assertThrows(IllegalStateException.class, () -> emulator.fastForwardTime(Duration.ofSeconds(1)));
            assertThrows(IllegalStateException.class, () -> emulator.setTime(Instant.EPOCH.plusSeconds(1)));
            assertDoesNotThrow(emulator::resetTime);
        }
    }

    @Test
    void contextNowUsesMicrosecondsFromUpdatedMutableTimeSource() {
        var start = Instant.parse("2026-10-07T10:00:00.000000600Z");
        var source = new MutableTimeSource(start);
        var seen = VariableType.of("seen", Instant.class);
        ProcessDefinition definition = builder -> builder.type("NOW")
            .initialState(State.START)
            .step(State.START, context -> context.setVariable(seen, context.now()), State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(source, definition)) {
            var emulator = new ProcessEngineEmulator(engine);
            var id = engine.newProcess(ProcessType.of("NOW")).start();
            var advance = Duration.ofHours(3);
            emulator.fastForwardTime(advance);
            assertEquals(start.plus(advance), source.now());

            var done = emulator.runUntilStop(id);

            assertEquals(Instant.parse("2026-10-07T13:00:00Z"), done.variables().find(seen).orElseThrow());
        }
    }

    @Test
    void runUntilStopWaitsForStepRetryDelay() {
        var start = Instant.parse("2026-10-07T10:00:00.000000600Z");
        var source = new MutableTimeSource(start);
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
        try (var engine = newProcessEngineWith(source, definition)) {
            var emulator = new ProcessEngineEmulator(engine);
            var id = engine.newProcess(ProcessType.of("STEP_RETRY")).start();

            var failed = emulator.runUntilStop(id);
            assertEquals(1, failed.attempt());
            var deadline = Instant.parse("2026-10-07T10:05:00.000001Z");
            assertEquals(deadline, failed.nextRunAt().orElseThrow());

            emulator.setTime(deadline.minusNanos(1));
            assertEquals(deadline.minusNanos(1), source.now());
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
