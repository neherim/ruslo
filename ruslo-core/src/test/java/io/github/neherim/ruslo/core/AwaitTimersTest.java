package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Event selection in an await when several events are due: the earliest wins; during retry backoff the due deadline wins.
 * The process enters the await at 11:00 with a deadline at 12:00.
 */
class AwaitTimersTest extends BaseTest {
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private final MutableClock clock = new MutableClock();
    private final List<String> log = new ArrayList<>();

    @Test
    void messageBeforeDeadlineWinsEvenIfHandledAfterIt() {
        var engine = engine("T1", (context, message) -> handled(message), null, null, noRetry());
        try {
            var id = start(engine, "T1", "11:00:00");
            at("11:59:00");
            engine.sendMessage(id, SIGNAL, "m");
            at("12:01:00");
            assertEquals("MSG_DONE", run(engine, id).state().name());
            assertEquals(List.of("m"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void deadlineWinsFailedMessageInRetryBackoff() {
        var engine = engine("T2", (context, message) -> {
            log.add(message.payload());
            throw new IllegalStateException("boom");
        }, null, null, ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)));
        try {
            var id = start(engine, "T2", "11:00:00");
            at("11:59:00");
            engine.sendMessage(id, SIGNAL, "first");
            run(engine, id);
            at("12:00:00");
            assertEquals("TIMEOUT_DONE", run(engine, id).state().name());
            assertEquals(List.of("first", "timeout"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void failedMessageBackoffIsNotShiftedByNewMessage() {
        var fail = new AtomicBoolean(true);
        var engine = engine("T2B", (context, message) -> {
            log.add(message.payload());
            if (fail.getAndSet(false)) throw new IllegalStateException("boom");
        }, null, null, ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)));
        try {
            var id = start(engine, "T2B", "11:00:00");
            at("11:30:00");
            engine.sendMessage(id, SIGNAL, "first");
            run(engine, id);
            at("11:32:00");
            engine.sendMessage(id, SIGNAL, "second");
            at("11:33:00");
            assertEquals("WAIT", run(engine, id).state().name());
            assertEquals(List.of("first"), log);
            at("11:35:00");
            assertEquals("MSG_DONE", run(engine, id).state().name());
            assertEquals(List.of("first", "first"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void selfTargetOnMessageLetsDeadlineFireNext() {
        var engine = engine(definition("T3", (context, message) -> log.add(message.payload()), State.WAIT,
                null, null, State.EVERY_DONE, noRetry()));
        try {
            var id = start(engine, "T3", "11:00:00");
            at("11:59:00");
            engine.sendMessage(id, SIGNAL, "m");
            at("12:01:00");
            assertEquals("WAIT", run(engine, id).state().name());
            assertEquals("TIMEOUT_DONE", run(engine, id).state().name());
            assertEquals(List.of("m", "timeout"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void messageAfterDeadlineLosesToTimeout() {
        var engine = engine("T4", (context, message) -> handled(message), null, null, noRetry());
        try {
            var id = start(engine, "T4", "11:00:00");
            at("12:00:30");
            engine.sendMessage(id, SIGNAL, "late");
            at("12:01:00");
            assertEquals("TIMEOUT_DONE", run(engine, id).state().name());
            assertEquals(List.of("timeout"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void messageWinsTieWithEveryAndTimeout() {
        var engine = engine("T5A", (context, message) -> handled(message), Duration.ofHours(1),
                context -> {}, noRetry());
        try {
            var id = start(engine, "T5A", "11:00:00");
            at("12:00:00");
            engine.sendMessage(id, SIGNAL, "m");
            assertEquals("MSG_DONE", run(engine, id).state().name());
        } finally {
            engine.close();
        }
    }

    @Test
    void everyWinsTieWithTimeout() {
        var engine = engine("T5B", (context, message) -> handled(message), Duration.ofHours(1),
                context -> {}, noRetry());
        try {
            var id = start(engine, "T5B", "11:00:00");
            at("12:00:00");
            assertEquals("EVERY_DONE", run(engine, id).state().name());
        } finally {
            engine.close();
        }
    }

    @Test
    void everySelfTargetDoesNotShiftDeadlineAndSkippedChecksAreNotBatched() {
        var engine = engine(definition("T6", (context, message) -> handled(message), State.MSG_DONE,
                Duration.ofMinutes(10), context -> log.add("every"), State.WAIT, noRetry()));
        try {
            var id = start(engine, "T6", "11:00:00");
            at("12:30:00");
            assertEquals("WAIT", run(engine, id).state().name());
            assertEquals("TIMEOUT_DONE", run(engine, id).state().name());
            assertEquals(List.of("every", "timeout"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void lateEveryIsProcessedBeforeDeadline() {
        var engine = engine("T7", (context, message) -> handled(message), Duration.ofMinutes(58),
                context -> {}, noRetry());
        try {
            var id = start(engine, "T7", "11:00:00");
            at("12:01:00");
            assertEquals("EVERY_DONE", run(engine, id).state().name());
        } finally {
            engine.close();
        }
    }

    @Test
    void everyWithZeroInitialDelayRunsOnEnteringAwait() {
        var engine = engine(everyDefinition("T12", Duration.ZERO, context -> log.add("every"), State.EVERY_DONE));
        try {
            var id = start(engine, "T12", "11:00:00");
            assertEquals("EVERY_DONE", run(engine, id).state().name());
            assertEquals(List.of("every"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void everyInitialDelayOnlyShiftsFirstCheck() {
        var engine = engine(everyDefinition("T13", Duration.ofSeconds(10), context -> log.add("every"), State.WAIT));
        try {
            var id = start(engine, "T13", "11:00:00");
            at("11:00:09");
            assertEquals("WAIT", run(engine, id).state().name());
            assertEquals(List.of(), log);
            at("11:00:10");
            run(engine, id);
            assertEquals(List.of("every"), log);
            at("11:10:09");
            run(engine, id);
            assertEquals(List.of("every"), log);
            at("11:10:10");
            run(engine, id);
            assertEquals(List.of("every", "every"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void lateEveryDoesNotShiftNextCheck() {
        var engine = engine(everyDefinition("T14", Duration.ofMinutes(10), context -> log.add("every"), State.WAIT));
        try {
            var id = start(engine, "T14", "11:00:00");
            at("11:10:05");
            run(engine, id);
            at("11:19:59");
            run(engine, id);
            assertEquals(List.of("every"), log);
            at("11:20:00");
            run(engine, id);
            assertEquals(List.of("every", "every"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void missedGridPointsAreSkipped() {
        var engine = engine(everyDefinition("T15", Duration.ofMinutes(10), context -> log.add("every"), State.WAIT));
        try {
            var id = start(engine, "T15", "11:00:00");
            at("11:35:00");
            run(engine, id);
            run(engine, id);
            at("11:39:59");
            run(engine, id);
            assertEquals(List.of("every"), log);
            at("11:40:00");
            run(engine, id);
            assertEquals(List.of("every", "every"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void everyOnDeadlinePointRunsBeforeTimeoutDespiteLateChecks() {
        var engine = engine(definition("T16", (context, message) -> handled(message), State.MSG_DONE,
                Duration.ofMinutes(10), context -> log.add("every"), State.WAIT, noRetry()));
        try {
            var id = start(engine, "T16", "11:00:00");
            for (var minute = 10; minute <= 50; minute += 10) {
                at("11:" + minute + ":05");
                assertEquals("WAIT", run(engine, id).state().name());
            }
            at("12:00:00");
            assertEquals("WAIT", run(engine, id).state().name());
            assertEquals("TIMEOUT_DONE", run(engine, id).state().name());
            assertEquals(List.of("every", "every", "every", "every", "every", "every", "timeout"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void everyRetryKeepsGrid() {
        var fail = new AtomicBoolean(true);
        var engine = engine(definition("T17", (context, message) -> handled(message), State.MSG_DONE,
                Duration.ofMinutes(10), context -> {
                    log.add("every");
                    if (fail.getAndSet(false)) throw new IllegalStateException("boom");
                }, State.WAIT, ProcessErrorHandler.fixed(1, Duration.ofMinutes(7))));
        try {
            var id = start(engine, "T17", "11:00:00");
            at("11:10:00");
            run(engine, id);
            at("11:17:00");
            run(engine, id);
            assertEquals(List.of("every", "every"), log);
            at("11:19:59");
            run(engine, id);
            assertEquals(List.of("every", "every"), log);
            at("11:20:00");
            run(engine, id);
            assertEquals(List.of("every", "every", "every"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void everyHandledExactlyOnGridPointSchedulesNextPoint() {
        var engine = engine(everyDefinition("T18", Duration.ofMinutes(10), context -> log.add("every"), State.WAIT));
        try {
            var id = start(engine, "T18", "11:00:00");
            at("11:10:00");
            run(engine, id);
            run(engine, id);
            assertEquals(List.of("every"), log);
            at("11:20:00");
            run(engine, id);
            assertEquals(List.of("every", "every"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void failedProcessIsNotWokenByDeadlineAndResumeRepeatsEarliestEvent() {
        var fail = new AtomicBoolean(true);
        var engine = engine("T9", (context, message) -> {
            if (fail.get()) throw new IllegalStateException("boom");
            handled(message);
        }, null, null, (context, error) -> ErrorResult.fail());
        try {
            var id = start(engine, "T9", "11:00:00");
            at("11:59:00");
            engine.sendMessage(id, SIGNAL, "m");
            assertEquals(ProcessStatus.FAILED, run(engine, id).status());
            at("12:30:00");
            assertThrows(ProcessStatusException.class, () -> run(engine, id));
            var failed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertEquals("WAIT", failed.state().name());
            fail.set(false);
            engine.resumeFailedProcess(id);
            assertEquals("MSG_DONE", run(engine, id).state().name());
            assertEquals(List.of("m"), log);
        } finally {
            engine.close();
        }
    }

    @Test
    void fallbackFromAwaitDropsDeadline() {
        ProcessDefinition definition = builder -> builder.type("T11")
            .onError(ProcessErrorHandler.noRetry(), State.FALLBACK)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await
                .message(SIGNAL, (context, message) -> {
                    throw new IllegalStateException("boom");
                }, State.MSG_DONE)
                .timeout(Duration.ofHours(1), State.TIMEOUT_DONE))
            .step(State.FALLBACK, context -> {}, State.MSG_DONE)
            .terminal(State.MSG_DONE)
            .terminal(State.TIMEOUT_DONE)
            .build();
        var engine = engine(definition);
        try {
            var id = start(engine, "T11", "11:00:00");
            at("11:30:00");
            engine.sendMessage(id, SIGNAL, "m");
            assertEquals("FALLBACK", run(engine, id).state().name());
            assertEquals("MSG_DONE", run(engine, id).state().name());
        } finally {
            engine.close();
        }
    }

    private ProcessDefinition definition(
            String type,
            ProcessMessageHandler<String> onMessage,
            State messageTarget,
            Duration every,
            ProcessTimerHandler onEvery,
            State everyTarget,
            ProcessErrorHandler errorHandler
    ) {
        return builder -> builder.type(type)
            .onError(errorHandler)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> {
                await.message(SIGNAL, onMessage, messageTarget);
                if (every != null) await.every(every, onEvery, everyTarget);
                await.timeout(Duration.ofHours(1), context -> log.add("timeout"), State.TIMEOUT_DONE);
            })
            .terminal(State.MSG_DONE)
            .terminal(State.EVERY_DONE)
            .terminal(State.TIMEOUT_DONE)
            .build();
    }

    private ProcessDefinition everyDefinition(
            String type,
            Duration initialDelay,
            ProcessTimerHandler onEvery,
            State everyTarget
    ) {
        return builder -> builder.type(type)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await
                .every(Duration.ofMinutes(10), onEvery, everyTarget,
                        options -> options.initialDelay(initialDelay))
                .timeout(Duration.ofHours(1), State.TIMEOUT_DONE))
            .terminal(State.EVERY_DONE)
            .terminal(State.TIMEOUT_DONE)
            .build();
    }

    private ProcessEngine engine(
            String type,
            ProcessMessageHandler<String> onMessage,
            Duration every,
            ProcessTimerHandler onEvery,
            ProcessErrorHandler errorHandler
    ) {
        return engine(definition(type, onMessage, State.MSG_DONE, every, onEvery, State.EVERY_DONE, errorHandler));
    }

    private ProcessEngine engine(ProcessDefinition definition) {
        return new ProcessEngineBuilder(database).withClock(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
    }

    private void at(String time) {
        clock.now = Instant.parse("2026-09-24T" + time + "Z");
    }

    private UUID start(ProcessEngine engine, String type, String time) {
        at(time);
        return engine.newProcess(ProcessType.of(type)).start();
    }

    private io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails run(ProcessEngine engine, UUID id) {
        return emulator(engine, 20).runOneStep(id);
    }

    private void handled(IncomingMessage<String> message) {
        log.add(message.payload());
    }

    private static ProcessErrorHandler noRetry() {
        return ProcessErrorHandler.noRetry();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private enum State implements ProcessState {
        WAIT,
        MSG_DONE,
        EVERY_DONE,
        TIMEOUT_DONE,
        FALLBACK
    }
}
