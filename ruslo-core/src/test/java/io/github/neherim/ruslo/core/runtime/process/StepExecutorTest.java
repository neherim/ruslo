package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.runtime.logging.MdcKeys;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance.AwaitEventKind;
import io.github.neherim.ruslo.core.runtime.worker.HandlerException;
import io.github.neherim.ruslo.core.runtime.worker.StepExecutor;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.*;

class StepExecutorTest {
    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private final MutableClock clock = new MutableClock();
    private final StepExecutor executor = new StepExecutor(new ProcessMdc(MdcKeys.defaults()),
            new ProcessEngineMetrics() {
            }, clock);

    @Test
    void stepEntersTarget() {
        var process = process(builder -> builder.step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, State.DONE)));
        assertTrue(executor.executeNext(process));
        assertEquals(State.WAIT.name(), process.getState().name());
        assertEquals(WorkerId.DEFAULT, process.getWorkerId().orElseThrow());
    }

    @Test
    void branchingStepEntersRoutedTarget() {
        var process = process(builder -> builder.step(State.START, context -> Outcome.B,
                routes -> routes.on(Outcome.A, State.WAIT).on(Outcome.B, State.DONE))
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, State.DONE)));
        assertTrue(executor.executeNext(process));
        assertEquals(ProcessStatus.COMPLETED, process.getStatus());
        assertTrue(process.getWorkerId().isEmpty());
    }

    @Test
    void nullOutcomeOfBranchingStepIsHandlerError() {
        var process = process(builder -> builder.step(State.START, context -> (Outcome) null,
                routes -> routes.on(Outcome.A, State.DONE).on(Outcome.B, State.DONE)));
        var error = assertThrows(HandlerException.class, () -> executor.executeNext(process)).getCause();
        assertTrue(error.getMessage().contains("Branching handler in state START of process EXEC_TEST"),
                error.getMessage());
        assertTrue(error.getMessage().contains("returned null"));
        assertEquals(State.START.name(), process.getState().name());
    }

    @Test
    void nullOutcomeOfBranchingEveryIsHandlerError() {
        var process = process(builder -> builder.step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.every(Interval.of(Duration.ofSeconds(5)).firstAfter(Duration.ZERO),
                    context -> (Outcome) null,
                    routes -> routes.on(Outcome.A, State.DONE).on(Outcome.B, State.WAIT))));
        executor.executeNext(process);
        var error = assertThrows(HandlerException.class, () -> executor.executeNext(process)).getCause();
        assertTrue(error.getMessage().contains("Branching handler in state WAIT of process EXEC_TEST"),
                error.getMessage());
    }

    @Test
    void selfTargetMessageIsConsumedWithoutReentry() {
        var process = awaitProcess(State.WAIT);
        process.appendMessage(SIGNAL, "a");
        process.appendMessage(SIGNAL, "b");
        var deadline = process.getAfterDueAt().orElseThrow();
        assertTrue(executor.executeNext(process));
        assertEquals(State.WAIT.name(), process.getState().name());
        var queued = process.getMessages().peekFirst().orElseThrow();
        assertEquals("b", ProcessMessages.convert(queued, SIGNAL).payload());
        assertEquals(deadline, process.getAfterDueAt().orElseThrow());
    }

    @Test
    void otherTargetMessageEntersStateAndClosesQueue() {
        var process = awaitProcess(State.DONE);
        process.appendMessage(SIGNAL, "a");
        process.appendMessage(SIGNAL, "b");
        assertTrue(executor.executeNext(process));
        assertTrue(process.getMessages().isEmpty());
        assertEquals(ProcessStatus.COMPLETED, process.getStatus());
    }

    @Test
    void messageReceivedBeforeDeadlineWinsWithoutBackoff() {
        var process = awaitProcess(State.DONE);
        process.appendMessage(SIGNAL, "a");
        clock.now = NOW.plus(Duration.ofMinutes(2));
        executor.executeNext(process);
        assertEquals(State.DONE.name(), process.getState().name());
    }

    @Test
    void retryBackoffKeepsOrderOfEvents() {
        var process = awaitProcess(State.DONE);
        process.appendMessage(SIGNAL, "a");
        process.applyErrorResult(ErrorResult.retryAfter(Duration.ofMinutes(10)), "boom");
        assertEquals(Optional.of(NOW.plus(Duration.ofMinutes(10))), process.getNextRunAt());
        clock.now = process.getNextRunAt().orElseThrow();
        executor.executeNext(process);
        assertEquals(State.DONE.name(), process.getState().name());
    }

    @ParameterizedTest
    @EnumSource(AwaitEventKind.class)
    void earliestFutureEventSchedulesNextRunAndExecutesOnlyWhenDue(AwaitEventKind earliest) {
        var timer = TimerType.of("DUE");
        var fired = VariableType.of("fired", AwaitEventKind.class);
        var messageDelay = Duration.ofSeconds(earliest == AwaitEventKind.MESSAGE ? 1 : 2);
        var everyDelay = Duration.ofSeconds(earliest == AwaitEventKind.EVERY ? 1 : 2);
        var afterDelay = Duration.ofSeconds(earliest == AwaitEventKind.AFTER ? 1 : 2);
        var atDelay = Duration.ofSeconds(earliest == AwaitEventKind.AT ? 1 : 2);
        var process = process(builder -> builder.step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL,
                    (context, message) -> context.setVariable(fired, AwaitEventKind.MESSAGE), State.WAIT)
                .every(everyDelay, context -> context.setVariable(fired, AwaitEventKind.EVERY), State.WAIT)
                .after(afterDelay, context -> context.setVariable(fired, AwaitEventKind.AFTER), State.WAIT)
                .at(timer, context -> context.setVariable(fired, AwaitEventKind.AT), State.WAIT)));
        executor.executeNext(process);
        process.setTimer(timer, NOW.plus(atDelay));
        clock.now = NOW.plus(messageDelay);
        process.appendMessage(SIGNAL, "future");
        clock.now = NOW;

        assertEquals(Optional.of(NOW.plusSeconds(1)), process.getNextRunAt());
        process.applyErrorResult(ErrorResult.retryAfter(Duration.ZERO), "retry without a due event");
        assertFalse(executor.executeNext(process));
        assertEquals(Optional.of(NOW.plusSeconds(1)), process.getNextRunAt());
        assertEquals(0, process.getFailedAttempts());
        assertTrue(process.getLastError().isEmpty());

        clock.now = NOW.plusSeconds(1);
        assertTrue(executor.executeNext(process));
        assertEquals(earliest, process.findVariable(fired).orElseThrow());
        assertEquals(Optional.of(NOW.plusSeconds(2)), process.getNextRunAt());
        assertEquals(earliest == AwaitEventKind.MESSAGE, process.getMessages().isEmpty());
    }

    /** A process awaiting in WAIT with a deadline in one minute; the message leads to {@code messageTarget}. */
    private ProcessInstance awaitProcess(State messageTarget) {
        var process = process(builder -> builder.step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, messageTarget)
                .after(Duration.ofMinutes(1), State.EXPIRED)));
        executor.executeNext(process);
        return process;
    }

    private ProcessInstance process(UnaryOperator<ProcessSpecBuilder.StatesBuilder> states) {
        clock.now = NOW;
        var spec = states.apply(new ProcessSpecBuilder().type("EXEC_TEST").initialState(State.START))
            .terminal(State.DONE)
            .terminal(State.EXPIRED)
            .build();
        return ProcessInstance.createNew(UUID.randomUUID(), null, spec,
                ProcessVariables.empty(),
                ProcessTimers.empty(),
                "correlation", NOW, clock);
    }

    private static final class MutableClock extends Clock {
        private Instant now = NOW;

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

    private enum Outcome {
        A,
        B
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        DONE,
        EXPIRED
    }
}
