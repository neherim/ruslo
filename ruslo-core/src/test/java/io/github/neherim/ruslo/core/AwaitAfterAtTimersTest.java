package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code after} timers and process timers of {@code at}: their moments, firing with the await itself as the target,
 * tie order and retries. The process enters the await at 11:00.
 */
class AwaitAfterAtTimersTest extends BaseTest {
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private static final MessageType<Instant> MOVE = MessageType.of("MOVE", Instant.class);
    private static final MessageType<String> CANCEL = MessageType.of("CANCEL", String.class);
    private static final TimerType DUE = TimerType.of("due");
    private final MutableClock clock = new MutableClock(ZoneOffset.UTC, instant("11:00:00"), ChronoUnit.MICROS);
    private final List<String> log = new ArrayList<>();
    private ProcessStep againStep = context -> {};
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
    void timerSetInStepFiresInAwaitAndWithoutTimerAwaitWaits() {
        againStep = context -> context.setTimer(DUE, instant("11:30:00"));
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.AGAIN).at(DUE, State.AT_DONE));
        var id = start();
        assertEquals(Optional.empty(), details(id).nextRunAt());
        at("11:10:00");
        engine.sendMessage(id, SIGNAL, "m");
        var details = emulator(engine).runUntilStop(id);
        assertEquals("WAIT", details.state().name());
        assertEquals(Map.of(DUE.name(), instant("11:30:00")), details.timers());
        assertEquals(Optional.of(instant("11:30:00")), details.nextRunAt());
        at("11:29:59");
        assertEquals("WAIT", run(id).state().name());
        at("11:30:00");
        var fired = run(id);
        assertEquals("AT_DONE", fired.state().name());
        assertEquals(Map.of(), fired.timers());
    }

    @Test
    void messageSettingTimerMovesAtWithoutReentry() {
        engine(await -> await.message(MOVE, (context, message) -> context.setTimer(DUE, message.payload()),
                State.WAIT)
            .at(DUE, State.AT_DONE));
        var id = start(instant("12:00:00"));
        at("11:10:00");
        engine.sendMessage(id, MOVE, instant("13:00:00"));
        engine.sendMessage(id, MOVE, instant("12:40:00"));
        assertEquals(Optional.of(instant("11:10:00")), run(id).nextRunAt());
        var details = run(id);
        assertEquals(Optional.of(instant("12:40:00")), details.nextRunAt());
        assertEquals(1, details.stateHistory().size());
        at("12:30:00");
        assertEquals("WAIT", run(id).state().name());
        at("12:40:00");
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void cancelTimerTurnsAtOff() {
        engine(await -> await.message(SIGNAL, (context, message) -> log.add(context.cancelTimer(DUE) + ""),
                State.WAIT)
            .at(DUE, State.AT_DONE));
        var id = start(instant("12:00:00"));
        engine.sendMessage(id, SIGNAL, "cancel");
        var details = run(id);
        assertEquals(Optional.empty(), details.nextRunAt());
        assertEquals(Map.of(), details.timers());
        at("13:00:00");
        assertEquals("WAIT", run(id).state().name());
        engine.sendMessage(id, SIGNAL, "cancel");
        run(id);
        assertEquals(List.of("true", "false"), log);
    }

    @Test
    void pastMomentOnEnteringFiresImmediately() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE).at(DUE, State.AT_DONE));
        var id = start(instant("10:00:00"));
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void handlerSeesFiredMomentAndSettingTimerAgainMakesSchedule() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> {
                var fired = context.getTimer(DUE).orElseThrow();
                log.add(fired.toString());
                if (log.size() < 2) context.setTimer(DUE, fired.plus(Duration.ofDays(1)));
            }, State.WAIT));
        var id = start(instant("12:00:00"));
        at("12:30:00");
        assertEquals(Optional.of(instant("12:00:00").plus(Duration.ofDays(1))), run(id).nextRunAt());
        clock.setTime(instant("12:00:00").plus(Duration.ofDays(1)));
        var details = run(id);
        assertEquals(List.of("2026-09-24T12:00:00Z", "2026-09-25T12:00:00Z"), log);
        assertEquals(Optional.empty(), details.nextRunAt());
        assertEquals(Map.of(), details.timers());
        assertEquals("WAIT", run(id).state().name());
        engine.sendMessage(id, SIGNAL, "cancel");
        assertEquals("MSG_DONE", run(id).state().name());
    }

    @Test
    void handlerSettingFiredMomentAgainDoesNotFireAgain() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> {
                log.add("at");
                context.setTimer(DUE, context.getTimer(DUE).orElseThrow());
            }, State.WAIT));
        var id = start(instant("12:00:00"));
        at("12:00:00");
        var details = emulator(engine).runUntilStop(id);
        assertEquals(Map.of(), details.timers());
        assertEquals(Optional.empty(), details.nextRunAt());
        assertEquals(List.of("at"), log);
    }

    @Test
    void awaitStepAwaitLoopDoesNotFireAgain() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> log.add("at"), State.AGAIN));
        var id = start(instant("11:30:00"));
        at("12:00:00");
        var details = emulator(engine).runUntilStop(id);
        assertEquals("WAIT", details.state().name());
        assertEquals(Optional.empty(), details.nextRunAt());
        assertEquals(List.of("at"), log);
    }

    @Test
    void failedAtHandlerKeepsTimerForRetry() {
        var fail = new AtomicBoolean(true);
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> {
                log.add("at");
                if (fail.getAndSet(false)) throw new IllegalStateException("boom");
            }, State.AT_DONE));
        var id = start(instant("12:00:00"));
        at("12:00:00");
        var failed = run(id);
        assertEquals(1, failed.attempt());
        assertEquals(Map.of(DUE.name(), instant("12:00:00")), failed.timers());
        assertEquals(Optional.of(instant("12:05:00")), failed.nextRunAt());
        at("12:05:00");
        assertEquals("AT_DONE", run(id).state().name());
        assertEquals(List.of("at", "at"), log);
    }

    @Test
    void successfulRetryOfAtSettingTimerAgainClearsErrorAndUsesNewMoment() {
        var fail = new AtomicBoolean(true);
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("boom");
                context.setTimer(DUE, instant("13:00:00"));
            }, State.WAIT));
        var id = start(instant("12:00:00"));
        at("12:00:00");
        assertEquals(1, run(id).attempt());
        at("12:05:00");
        var details = run(id);
        assertEquals(0, details.attempt());
        assertEquals(Optional.empty(), details.lastError());
        assertEquals(Optional.of(instant("13:00:00")), details.nextRunAt());
    }

    @Test
    void errorHandlerSettingTimerDoesNotWakeProcessBeforeRetry() {
        engine((context, error) -> {
            context.setTimer(DUE, instant("11:31:00"));
            return ErrorResult.retryAfter(Duration.ofMinutes(5));
        }, await -> await.message(SIGNAL, (context, message) -> {
            throw new IllegalStateException("boom");
        }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("13:00:00"));
        at("11:30:00");
        engine.sendMessage(id, SIGNAL, "m");
        var details = run(id);
        assertEquals(Optional.of(instant("11:35:00")), details.nextRunAt());
        assertEquals(Map.of(DUE.name(), instant("11:31:00")), details.timers());
        at("11:31:00");
        assertEquals(1, run(id).attempt());
    }

    @Test
    void errorHandlerCancellingFailedAtLeavesNoEventAfterBackoff() {
        engine((context, error) -> {
            context.cancelTimer(DUE);
            return ErrorResult.retryAfter(Duration.ofMinutes(5));
        }, await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> {
                log.add("at");
                throw new IllegalStateException("boom");
            }, State.AT_DONE));
        var id = start(instant("12:00:00"));
        at("12:00:00");
        var failed = run(id);
        assertEquals(1, failed.attempt());
        assertEquals(Map.of(), failed.timers());
        at("12:05:00");
        var details = run(id);
        assertEquals("WAIT", details.state().name());
        assertEquals(0, details.attempt());
        assertEquals(Optional.empty(), details.nextRunAt());
        assertEquals(List.of("at"), log);
        engine.sendMessage(id, SIGNAL, "m");
        assertEquals("MSG_DONE", run(id).state().name());
    }

    @Test
    void timerLeftByMessageFiresOnReentryUnlessCancelled() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.AGAIN)
            .message(CANCEL, (context, message) -> context.cancelTimer(DUE), State.AGAIN)
            .at(DUE, State.AT_DONE));
        var kept = start(instant("12:00:00"));
        var cancelled = start(instant("12:00:00"));
        at("11:10:00");
        engine.sendMessage(kept, SIGNAL, "leave");
        engine.sendMessage(cancelled, CANCEL, "leave");
        run(kept);
        run(cancelled);
        at("12:30:00");
        assertEquals("AT_DONE", emulator(engine).runUntilStop(kept).state().name());
        var details = emulator(engine).runUntilStop(cancelled);
        assertEquals("WAIT", details.state().name());
        assertEquals(Optional.empty(), details.nextRunAt());
    }

    @Test
    void moveToCurrentAwaitKeepsQueueAndRetriesFailedMessage() {
        var fail = new AtomicBoolean(true);
        engine(await -> await.message(SIGNAL, (context, message) -> {
            if (fail.get()) throw new IllegalStateException("boom");
        }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("12:00:00"));
        at("11:10:00");
        engine.sendMessage(id, SIGNAL, "m");
        assertEquals(ProcessStatus.FAILED, run(id).status());
        at("12:30:00");
        fail.set(false);
        new ProcessEngineAdmin(engine).moveProcessToState(id, State.WAIT);
        var moved = details(id);
        assertEquals(ProcessStatus.ACTIVE, moved.status());
        assertEquals(Optional.of(instant("11:10:00")), moved.nextRunAt());
        assertEquals(1, moved.stateHistory().size());
        assertEquals("MSG_DONE", run(id).state().name());
    }

    @Test
    void giveUpFromAwaitKeepsTimer() {
        startEngine(builder -> builder.type("TIMERS")
            .onError((context, error) -> ErrorResult.giveUp())
            .onGiveUp(State.MSG_DONE)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.AT_DONE)
                .at(DUE, State.AT_DONE))
            .terminal(State.MSG_DONE)
            .terminal(State.AT_DONE)
            .build());
        var id = start(instant("12:00:00"));
        engine.sendMessage(id, SIGNAL, "m");
        var details = run(id);
        assertEquals("MSG_DONE", details.state().name());
        assertEquals(Map.of(DUE.name(), instant("12:00:00")), details.timers());
    }

    @Test
    void storedTimerUnknownToDefinitionDoesNotAffectExecution() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE).at(DUE, State.AT_DONE));
        var id = start(instant("12:00:00"));
        database.update("update xs_process_instance set timers = ? where id = ?",
                "{\"due\":\"2026-09-24T12:00:00Z\",\"old\":\"2026-09-24T11:00:00Z\"}", id);
        assertEquals(Map.of(DUE.name(), instant("12:00:00"), "old", instant("11:00:00")), details(id).timers());
        assertEquals("WAIT", run(id).state().name());
        var admin = new ProcessEngineAdmin(engine);
        assertTrue(admin.cancelProcessTimer(id, TimerType.of("old")));
        assertFalse(admin.cancelProcessTimer(id, TimerType.of("old")));
        assertEquals(Map.of(DUE.name(), instant("12:00:00")), details(id).timers());
        at("12:00:00");
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void momentWithNanosecondsIsTruncatedToMicroseconds() {
        engine(await -> await.message(SIGNAL, (context, message) -> log.add(context.getTimer(DUE)
            .orElseThrow()
            .toString()), State.WAIT)
            .message(CANCEL, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, context -> log.add("at"), State.WAIT));
        var id = start(Instant.parse("2026-09-24T11:30:00.123456789Z"));
        var truncated = Instant.parse("2026-09-24T11:30:00.123456Z");
        assertEquals(Map.of(DUE.name(), truncated), details(id).timers());
        assertEquals(Optional.of(truncated), details(id).nextRunAt());
        engine.sendMessage(id, SIGNAL, "m");
        run(id);
        assertEquals(List.of(truncated.toString()), log);
        clock.setTime(truncated);
        run(id);
        run(id);
        assertEquals(List.of(truncated.toString(), "at"), log);
        assertEquals(Optional.empty(), details(id).nextRunAt());
    }

    @Test
    void adminTimerOnCompletedOrFailedProcessDoesNotWakeIt() {
        engine((context, error) -> ErrorResult.fail(), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .message(MOVE, (context, message) -> {}, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var admin = new ProcessEngineAdmin(engine);
        var completed = start();
        engine.sendMessage(completed, MOVE, instant("11:00:00"));
        run(completed);
        admin.setProcessTimer(completed, DUE, instant("11:00:00"));
        assertEquals(Map.of(DUE.name(), instant("11:00:00")), details(completed).timers());
        assertEquals(Optional.empty(), details(completed).nextRunAt());

        var failed = start(instant("13:00:00"));
        at("11:30:00");
        engine.sendMessage(failed, SIGNAL, "m");
        assertEquals(ProcessStatus.FAILED, run(failed).status());
        admin.setProcessTimer(failed, DUE, instant("11:20:00"));
        assertEquals(Optional.empty(), details(failed).nextRunAt());
        engine.resumeFailedProcess(failed);
        assertEquals(Optional.of(instant("11:20:00")), details(failed).nextRunAt());
    }

    @Test
    void timerShortenedDuringBackoffDoesNotWakeProcess() {
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
        new ProcessEngineAdmin(engine).setProcessTimer(id, DUE, instant("11:32:00"));
        assertEquals(Optional.of(instant("11:35:00")), details(id).nextRunAt());
        at("11:32:00");
        assertEquals(1, run(id).attempt());
        at("11:35:00");
        assertEquals(2, run(id).attempt());
        var failed = retryUntilFailed(id);
        assertEquals("WAIT", failed.state().name());
    }

    @Test
    void timerMovedBeforeFailedMessageFiresAfterBackoff() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("13:00:00"));
        at("11:30:00");
        engine.sendMessage(id, SIGNAL, "m");
        run(id);
        new ProcessEngineAdmin(engine).setProcessTimer(id, DUE, instant("11:20:00"));
        at("11:35:00");
        assertEquals("AT_DONE", run(id).state().name());
    }

    @Test
    void timerMovedLaterDuringBackoffKeepsRetryTime() {
        engine(ProcessErrorHandler.fixed(3, Duration.ofMinutes(5)), await -> await
            .message(SIGNAL, (context, message) -> {
                throw new IllegalStateException("boom");
            }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start(instant("11:40:00"));
        at("11:30:00");
        engine.sendMessage(id, SIGNAL, "m");
        run(id);
        new ProcessEngineAdmin(engine).setProcessTimer(id, DUE, instant("12:30:00"));
        assertEquals(Optional.of(instant("11:35:00")), details(id).nextRunAt());
    }

    @Test
    void oneTimerInTwoAwaits() {
        startEngine(builder -> builder.type("TIMERS")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, State.WAIT_MORE)
                .at(DUE, State.AT_DONE))
            .await(State.WAIT_MORE, await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE)
                .at(DUE, State.AFTER_DONE))
            .terminal(State.MSG_DONE)
            .terminal(State.AFTER_DONE)
            .terminal(State.AT_DONE)
            .build());
        var id = start(instant("12:00:00"));
        engine.sendMessage(id, SIGNAL, "next");
        assertEquals(Optional.of(instant("12:00:00")), run(id).nextRunAt());
        at("12:00:00");
        assertEquals("AFTER_DONE", run(id).state().name());
    }

    @Test
    void undeclaredTimerIsRejected() {
        var other = TimerType.of("other");
        var expected = "Timer other is not used by any at of process TIMERS";
        engine(await -> await.message(SIGNAL, (context, message) -> {
            log.add(assertThrows(IllegalArgumentException.class, () -> context.setTimer(other, context.now()))
                .getMessage());
            log.add(assertThrows(IllegalArgumentException.class, () -> context.getTimer(other)).getMessage());
            log.add(assertThrows(IllegalArgumentException.class, () -> context.cancelTimer(other)).getMessage());
            log.add(assertThrows(NullPointerException.class, () -> context.setTimer(DUE, null)).getClass()
                .getSimpleName());
        }, State.MSG_DONE)
            .at(DUE, State.AT_DONE));
        var id = start();
        engine.sendMessage(id, SIGNAL, "m");
        assertEquals("MSG_DONE", run(id).state().name());
        assertEquals(List.of(expected, expected, expected, "NullPointerException"), log);

        var startWithOther = engine.newProcess(ProcessType.of("TIMERS"))
            .businessKey("other")
            .timer(other, instant("12:00:00"));
        assertEquals(expected, assertThrows(IllegalArgumentException.class, startWithOther::start).getMessage());
        assertEquals(List.of(), engine.processes().byBusinessKey("other").list());
        assertThrows(NullPointerException.class, () -> engine.newProcess(ProcessType.of("TIMERS")).timer(DUE, null));
        var waiting = start();
        assertEquals(expected, assertThrows(IllegalArgumentException.class,
                () -> new ProcessEngineAdmin(engine).setProcessTimer(waiting, other, instant("12:00:00")))
            .getMessage());
    }

    @Test
    void variableNamedLikeTimerIsPlainVariable() {
        engine(await -> await.message(SIGNAL, (context, message) -> {}, State.MSG_DONE).at(DUE, State.AT_DONE));
        at("11:00:00");
        var id = engine.newProcess(ProcessType.of("TIMERS")).variable(DUE.name(), "next Monday").start();
        var details = details(id);
        assertEquals(Map.of(), details.timers());
        assertEquals(Optional.empty(), details.nextRunAt());
        assertEquals(Optional.of("next Monday"), details.variables().get(VariableType.of(DUE.name(), String.class)));
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
        startEngine(builder -> builder.type("TIMERS")
            .onError(errorHandler)
            .initialState(State.WAIT)
            .await(State.WAIT, await)
            .step(State.AGAIN, againStep, State.WAIT)
            .terminal(State.MSG_DONE)
            .terminal(State.AFTER_DONE)
            .terminal(State.AT_DONE)
            .build());
    }

    private void startEngine(ProcessDefinition definition) {
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
        return engine.newProcess(ProcessType.of("TIMERS")).timer(DUE, due).start();
    }

    private ProcessInstanceDetails run(UUID id) {
        return emulator(engine, 20).runOneStep(id);
    }

    /** Runs every retry at its time until the process stops in {@code FAILED}. */
    private ProcessInstanceDetails retryUntilFailed(UUID id) {
        var details = details(id);
        for (var i = 0; i < 10 && details.status() == ProcessStatus.ACTIVE; i++) {
            clock.setTime(details.nextRunAt().orElseThrow());
            details = run(id);
        }
        assertEquals(ProcessStatus.FAILED, details.status());
        return details;
    }

    private ProcessInstanceDetails details(UUID id) {
        return engine.processes().byId(id).singleDetails().orElseThrow();
    }

    private void at(String time) {
        clock.setTime(instant(time));
    }

    private static Instant instant(String time) {
        return Instant.parse("2026-09-24T" + time + "Z");
    }

    private enum State implements ProcessState {
        WAIT,
        WAIT_MORE,
        AGAIN,
        MSG_DONE,
        AFTER_DONE,
        AT_DONE
    }
}
