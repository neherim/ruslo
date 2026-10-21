package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.runtime.logging.MdcKeys;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.*;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;

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
        assertTrue(executor.executeNextProcessStep(process));
        assertEquals(State.WAIT.name(), process.getState().name());
        assertEquals(WorkerId.DEFAULT, process.getWorkerId().orElseThrow());
    }

    @Test
    void branchingStepEntersRoutedTarget() {
        var process = process(builder -> builder.step(State.START, context -> Outcome.B,
                routes -> routes.on(Outcome.A, State.WAIT).on(Outcome.B, State.DONE))
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, State.DONE)));
        assertTrue(executor.executeNextProcessStep(process));
        assertEquals(ProcessStatus.COMPLETED, process.getStatus());
        assertTrue(process.getWorkerId().isEmpty());
    }

    @Test
    void nullOutcomeOfBranchingStepIsHandlerError() {
        var process = process(builder -> builder.step(State.START, context -> (Outcome) null,
                routes -> routes.on(Outcome.A, State.DONE).on(Outcome.B, State.DONE)));
        var error = assertThrows(HandlerException.class, () -> executor.executeNextProcessStep(process)).getCause();
        assertTrue(error.getMessage().contains("Branching step in state START of process EXEC_TEST"),
                error.getMessage());
        assertTrue(error.getMessage().contains("returned null"));
        assertEquals(State.START.name(), process.getState().name());
    }

    @Test
    void nullOutcomeOfBranchingEveryIsHandlerError() {
        var process = process(builder -> builder.step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.every(Duration.ofSeconds(5), context -> (Outcome) null,
                    routes -> routes.on(Outcome.A, State.DONE).on(Outcome.B, State.WAIT),
                    options -> options.initialDelay(Duration.ZERO))));
        executor.executeNextProcessStep(process);
        var error = assertThrows(HandlerException.class, () -> executor.executeNextProcessStep(process)).getCause();
        assertTrue(error.getMessage().contains("Branching every in state WAIT"), error.getMessage());
    }

    @Test
    void selfTargetMessageIsConsumedWithoutReentry() {
        var process = awaitProcess(State.WAIT);
        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "a", first, NOW);
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "b", second, NOW);
        var deadline = process.getAfterDueAt().orElseThrow();
        assertTrue(executor.executeNextProcessStep(process));
        assertEquals(State.WAIT.name(), process.getState().name());
        assertEquals(second, process.getMessages().peekFirst().orElseThrow().id());
        assertEquals(deadline, process.getAfterDueAt().orElseThrow());
    }

    @Test
    void otherTargetMessageEntersStateAndClosesQueue() {
        var process = awaitProcess(State.DONE);
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "a", UUID.randomUUID(), NOW);
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "b", UUID.randomUUID(), NOW);
        assertTrue(executor.executeNextProcessStep(process));
        assertTrue(process.getMessages().isEmpty());
        assertEquals(ProcessStatus.COMPLETED, process.getStatus());
    }

    @Test
    void messageReceivedBeforeDeadlineWinsWithoutBackoff() {
        var process = awaitProcess(State.DONE);
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "a", UUID.randomUUID(), NOW);
        clock.now = NOW.plus(Duration.ofMinutes(2));
        executor.executeNextProcessStep(process);
        assertEquals(State.DONE.name(), process.getState().name());
    }

    @Test
    void retryBackoffKeepsOrderOfEvents() {
        var process = awaitProcess(State.DONE);
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "a", UUID.randomUUID(), NOW);
        process.scheduleRetryAfter(Duration.ofMinutes(10), "boom");
        assertEquals(Optional.of(NOW.plus(Duration.ofMinutes(10))), process.getNextRunAt());
        clock.now = process.getNextRunAt().orElseThrow();
        executor.executeNextProcessStep(process);
        assertEquals(State.DONE.name(), process.getState().name());
    }

    /** A process awaiting in WAIT with a deadline in one minute; the message leads to {@code messageTarget}. */
    private ProcessInstance awaitProcess(State messageTarget) {
        var process = process(builder -> builder.step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, messageTarget)
                .after(Duration.ofMinutes(1), State.EXPIRED)));
        executor.executeNextProcessStep(process);
        return process;
    }

    private ProcessInstance process(UnaryOperator<ProcessSpecBuilder.StatesBuilder> states) {
        clock.now = NOW;
        var spec = states.apply(new ProcessSpecBuilder().type("EXEC_TEST").initialState(State.START))
            .terminal(State.DONE)
            .terminal(State.EXPIRED)
            .build();
        return ProcessInstance.createNew(UUID.randomUUID(), null, spec,
                ProcessVariables.empty(JsonUtils.DEFAULT_OBJECT_MAPPER),
                ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER), "correlation", NOW, clock);
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
