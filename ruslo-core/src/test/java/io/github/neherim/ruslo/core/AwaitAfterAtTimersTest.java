package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code after} and {@code at} timers: their moments, "once per moment", tie order and retries. The process enters
 * the await at 11:00.
 */
class AwaitAfterAtTimersTest extends BaseTest {
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private static final MessageType<Instant> MOVE = MessageType.of("MOVE", Instant.class);
    private static final VariableType<Instant> DUE = VariableType.of("due", Instant.class);
    private final TestClock clock = new TestClock();
    private final List<String> log = new ArrayList<>();
    private ProcessEngine engine;

    @AfterEach
    void closeEngine() {
        if (engine != null) engine.close();
    }

    @Test
    void afterFiresDelayAfterEnteringAwait() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.WAIT)
            .after(Duration.ofHours(1), State.AFTER_DONE));
        var id = start();
        at("11:59:59");
        assertEquals("WAIT", run(id).state().name());
        at("12:00:00");
        assertEquals("AFTER_DONE", run(id).state().name());
    }

    @Test
    void afterWithSelfTargetFiresOncePerEntry() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.AGAIN)
            .after(Duration.ofHours(1), context -> log.add("after"), State.WAIT));
        var id = start();
        at("12:00:00");
        assertEquals("WAIT", run(id).state().name());
        assertEquals(List.of("after"), log);
        assertEquals(Optional.empty(), details(id).nextRunAt());
        at("14:00:00");
        engine.sendMessage(id, SIGNAL, "again");
        assertEquals("WAIT", emulator(engine).runUntilStop(id).state().name());
        assertEquals(Optional.of(instant("15:00:00")), details(id).nextRunAt());
        at("15:00:00");
        run(id);
        assertEquals(List.of("after", "after"), log);
    }

    @Test
    void atFiresAtVariableMomentAndWithoutVariableThereIsNoTimer() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE).at(DUE, State.AT_DONE));
        var withoutVariable = start();
        assertEquals(Optional.empty(), details(withoutVariable).nextRunAt());
        var id = start(instant("11:30:00"));
        assertEquals(Optional.of(instant("11:30:00")), details(id).nextRunAt());
        at("11:29:59");
        assertEquals("WAIT", run(id).state().name());
        at("11:30:00");
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void messageWritingVariableMovesAtTimer() {
        engine(await -> await.message(MOVE, (context, message) -> context.setVariable(DUE, message.payload()),
                State.WAIT)
            .at(DUE, State.AT_DONE));
        var id = start(instant("12:00:00"));
        at("11:10:00");
        engine.sendMessage(id, MOVE, instant("13:00:00"));
        run(id);
        assertEquals(Optional.of(instant("13:00:00")), details(id).nextRunAt());
        at("12:30:00");
        assertEquals("WAIT", run(id).state().name());
        engine.sendMessage(id, MOVE, instant("12:40:00"));
        run(id);
        assertEquals(Optional.of(instant("12:40:00")), details(id).nextRunAt());
        at("12:40:00");
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void atHandlerWritingNextMomentMakesSchedule() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> {
                log.add("at");
                if (log.size() < 2) context.setVariable(DUE, context.getVariable(DUE).plus(Duration.ofDays(1)));
            }, State.WAIT));
        var id = start(instant("12:00:00"));
        at("12:00:00");
        run(id);
        assertEquals(Optional.of(instant("12:00:00").plus(Duration.ofDays(1))), details(id).nextRunAt());
        clock.now = instant("12:00:00").plus(Duration.ofDays(1));
        run(id);
        assertEquals(List.of("at", "at"), log);
        assertEquals(Optional.empty(), details(id).nextRunAt());
        assertEquals("WAIT", run(id).state().name());
        engine.sendMessage(id, SIGNAL, "cancel");
        assertEquals("MSG_DONE", run(id).state().name());
    }

    @Test
    void writingSameValueDoesNotRefireAt() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> {
                log.add("at");
                context.setVariable(DUE, context.getVariable(DUE));
            }, State.WAIT));
        var id = start(instant("11:30:00"));
        at("11:30:00");
        run(id);
        run(id);
        new ProcessEngineAdmin(engine).setProcessVariable(id, DUE, instant("11:30:00"));
        run(id);
        assertEquals(List.of("at"), log);
        assertEquals(Optional.empty(), details(id).nextRunAt());
    }

    @Test
    void pastMomentOnEnteringFiresImmediately() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE).at(DUE, State.AT_DONE));
        var id = start(instant("10:00:00"));
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void adminRemoveTurnsAtOffAndBadValueIsRejected() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE).at(DUE, State.AT_DONE));
        var id = start(instant("12:00:00"));
        var admin = new ProcessEngineAdmin(engine);
        var text = VariableType.of(DUE.name(), String.class);
        assertThrows(RuntimeException.class, () -> admin.setProcessVariable(id, text, "next Monday"));
        assertEquals(Optional.of(instant("12:00:00")), details(id).variables().get(DUE));
        assertEquals(Optional.of(instant("12:00:00")), details(id).nextRunAt());
        assertTrue(admin.removeProcessVariable(id, DUE.name()));
        assertEquals(Optional.empty(), details(id).nextRunAt());
        at("13:00:00");
        assertEquals("WAIT", run(id).state().name());
    }

    @Test
    void momentWithNanosecondsFiresOnce() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> log.add("at"), State.WAIT));
        var id = start(Instant.parse("2026-09-24T11:30:00.123456789Z"));
        at("11:31:00");
        run(id);
        run(id);
        assertEquals(List.of("at"), log);
        assertEquals(Optional.empty(), details(id).nextRunAt());
    }

    @Test
    void tieOrderIsMessageEveryAfterAt() {
        engine(await -> await.message(SIGNAL, (context, message) -> log.add("message"), State.WAIT)
            .every(Duration.ofHours(1), context -> log.add("every"), State.WAIT)
            .after(Duration.ofHours(1), context -> log.add("after"), State.WAIT)
            .at(DUE, context -> log.add("at"), State.AT_DONE));
        var id = start(instant("12:00:00"));
        at("12:00:00");
        engine.sendMessage(id, SIGNAL, "m");
        for (var i = 0; i < 4; i++)
            run(id);
        assertEquals(List.of("message", "every", "after", "at"), log);
        assertEquals("AT_DONE", details(id).state().name());
    }

    @Test
    void failedAfterWaitsForBackoff() {
        var fail = new AtomicBoolean(true);
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .after(Duration.ofHours(1), context -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("boom");
            }, State.AFTER_DONE));
        var id = start();
        at("12:00:00");
        assertEquals(1, run(id).attempt());
        at("12:04:59");
        assertEquals("WAIT", run(id).state().name());
        at("12:05:00");
        assertEquals("AFTER_DONE", run(id).state().name());
    }

    @Test
    void dueAtWaitsForBackoffOfFailedAfter() {
        var fail = new AtomicBoolean(true);
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .after(Duration.ofHours(1), context -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("boom");
            }, State.AFTER_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("12:02:00"));
        at("12:00:00");
        run(id);
        assertEquals(Optional.of(instant("12:05:00")), details(id).nextRunAt());
        at("12:02:00");
        assertEquals("WAIT", run(id).state().name());
        at("12:05:00");
        assertEquals("AFTER_DONE", run(id).state().name());
    }

    @Test
    void failedMessageIsRetriedBeforeDueAfter() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {
                log.add("message");
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .after(Duration.ofHours(1), State.AFTER_DONE));
        var id = start();
        at("11:59:00");
        engine.sendMessage(id, SIGNAL, "m");
        at("12:01:00");
        assertEquals(1, run(id).attempt());
        assertEquals(Optional.of(instant("12:06:00")), details(id).nextRunAt());
        var failed = retryUntilFailed(id);
        assertEquals("WAIT", failed.state().name());
        assertEquals(List.of("message", "message", "message", "message"), log);
    }

    @Test
    void longMessageAttemptOutlivingAfterIsRetriedFirst() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {
                log.add("message");
                if (log.size() == 1) {
                    at("12:01:00");
                    throw new IllegalStateException("boom");
                }
            }, State.MSG_DONE)
            .after(Duration.ofHours(1), State.AFTER_DONE));
        var id = start();
        at("11:59:00");
        engine.sendMessage(id, SIGNAL, "m");
        run(id);
        assertEquals(Optional.of(instant("12:06:00")), details(id).nextRunAt());
        at("12:06:00");
        assertEquals("MSG_DONE", run(id).state().name());
    }

    @Test
    void messageDuringBackoffDoesNotChangeNextRun() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(30)), await -> await
            .message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .after(Duration.ofHours(1), context -> {
                throw new IllegalStateException("boom");
            }, State.AFTER_DONE));
        var id = start();
        at("12:00:00");
        run(id);
        at("12:10:00");
        engine.sendMessage(id, SIGNAL, "m");
        assertEquals(Optional.of(instant("12:30:00")), details(id).nextRunAt());
        assertEquals("WAIT", run(id).state().name());
    }

    @Test
    void atShortenedDuringBackoffDoesNotWakeProcess() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("13:00:00"));
        at("11:30:00");
        engine.sendMessage(id, SIGNAL, "m");
        run(id);
        assertEquals(Optional.of(instant("11:35:00")), details(id).nextRunAt());
        new ProcessEngineAdmin(engine).setProcessVariable(id, DUE, instant("11:32:00"));
        assertEquals(Optional.of(instant("11:35:00")), details(id).nextRunAt());
        at("11:32:00");
        assertEquals(1, run(id).attempt());
        at("11:35:00");
        assertEquals(2, run(id).attempt());
        var failed = retryUntilFailed(id);
        assertEquals("WAIT", failed.state().name());
    }

    @Test
    void atMovedBeforeFailedMessageFiresAfterBackoff() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("13:00:00"));
        at("11:30:00");
        engine.sendMessage(id, SIGNAL, "m");
        run(id);
        new ProcessEngineAdmin(engine).setProcessVariable(id, DUE, instant("11:20:00"));
        at("11:35:00");
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void atMovedLaterDuringBackoffKeepsRetryTime() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("11:40:00"));
        at("11:30:00");
        engine.sendMessage(id, SIGNAL, "m");
        run(id);
        new ProcessEngineAdmin(engine).setProcessVariable(id, DUE, instant("12:30:00"));
        assertEquals(Optional.of(instant("11:35:00")), details(id).nextRunAt());
    }

    @Test
    void everyDoesNotRunDuringBackoff() {
        engine(ProcessErrorHandler.fixed(1, Duration.ofMinutes(30)), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .every(Duration.ofMinutes(10), context -> log.add("every"), State.WAIT)
            .after(Duration.ofHours(1), State.AFTER_DONE));
        var id = start();
        at("11:05:00");
        engine.sendMessage(id, SIGNAL, "m");
        assertEquals(1, run(id).attempt());
        at("11:10:00");
        assertEquals(1, run(id).attempt());
        assertEquals(Optional.of(instant("11:35:00")), details(id).nextRunAt());
        at("11:35:00");
        assertEquals(ProcessStatus.FAILED, run(id).status());
        assertEquals(List.of(), log);
    }

    @Test
    void failedProcessIgnoresAtChangeAndResumeRecomputesNextRun() {
        engine((context, error) -> ErrorResult.fail(), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("13:00:00"));
        at("11:30:00");
        engine.sendMessage(id, SIGNAL, "m");
        assertEquals(ProcessStatus.FAILED, run(id).status());
        new ProcessEngineAdmin(engine).setProcessVariable(id, DUE, instant("12:00:00"));
        assertEquals(Optional.empty(), details(id).nextRunAt());
        engine.resumeFailedProcess(id);
        assertEquals(Optional.of(instant("11:30:00")), details(id).nextRunAt());
    }

    @Test
    void manualRunWithoutDueEventOnlyRecomputesNextRun() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .after(Duration.ofHours(1), State.AFTER_DONE));
        var id = start();
        at("11:10:00");
        database.update("update xs_process_instance set next_run_at = ? where id = ?",
                Timestamp.from(instant("11:10:00")), id);
        var details = run(id);
        assertEquals("WAIT", details.state().name());
        assertEquals(0, details.attempt());
        assertEquals(1, details.stateHistory().size());
        assertEquals(Optional.of(instant("12:00:00")), details.nextRunAt());
        assertEquals(Optional.of(instant("12:00:00")), emulator(engine).runUntilStop(id).nextRunAt());
    }

    private void engine(Consumer<ProcessSpecBuilder.AwaitBuilder> await) {
        engine(ProcessErrorHandler.noRetry(), await);
    }

    private void engine(ProcessErrorHandler errorHandler, Consumer<ProcessSpecBuilder.AwaitBuilder> await) {
        ProcessDefinition definition = builder -> builder.type("TIMERS")
            .onError(errorHandler)
            .initialState(State.WAIT)
            .await(State.WAIT, await)
            .step(State.AGAIN, context -> {}, State.WAIT)
            .terminal(State.MSG_DONE)
            .terminal(State.AFTER_DONE)
            .terminal(State.AT_DONE)
            .build();
        engine = new ProcessEngineBuilder(database).withClock(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
    }

    private UUID start() {
        at("11:00:00");
        return engine.newProcess(ProcessType.of("TIMERS")).start();
    }

    private UUID start(Instant due) {
        at("11:00:00");
        return engine.newProcess(ProcessType.of("TIMERS")).variable(DUE, due).start();
    }

    private ProcessInstanceDetails run(UUID id) {
        return emulator(engine, 20).runOneStep(id);
    }

    /** Runs every retry at its time until the process stops in {@code FAILED}. */
    private ProcessInstanceDetails retryUntilFailed(UUID id) {
        var details = details(id);
        for (var i = 0; i < 10 && details.status() == ProcessStatus.ACTIVE; i++) {
            clock.now = details.nextRunAt().orElseThrow();
            details = run(id);
        }
        assertEquals(ProcessStatus.FAILED, details.status());
        return details;
    }

    private ProcessInstanceDetails details(UUID id) {
        return engine.processes().byId(id).singleDetails().orElseThrow();
    }

    private void at(String time) {
        clock.now = instant(time);
    }

    private static Instant instant(String time) {
        return Instant.parse("2026-09-24T" + time + "Z");
    }

    private static final class TestClock extends Clock {
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
        AGAIN,
        MSG_DONE,
        AFTER_DONE,
        AT_DONE
    }
}
