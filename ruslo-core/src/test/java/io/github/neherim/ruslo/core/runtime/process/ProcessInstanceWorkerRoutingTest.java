package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessInstanceWorkerRoutingTest extends BaseTest {
    private static final ProcessType TYPE = ProcessType.of("ROUTING");
    private static final WorkerId ACTION_WORKER = new WorkerId("ACTION");
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private static final TimerType DUE = TimerType.of("DUE");

    private final MutableClock clock = new MutableClock();

    @Test
    void stateEntryChangesRouteScheduleAndQueueAtomically() {
        var factory = factory();
        var dao = new ProcessInstanceDao(database, Clock.systemUTC(), factory, true,
                new io.github.neherim.ruslo.core.spi.ProcessEngineMetrics() {
                });
        var now = Instant.parse("2026-09-24T10:00:00Z");
        clock.setTime(now);
        var process = factory.newInstance(TYPE, now, null, "routing", Map.of(), Map.of());
        assertEquals(ACTION_WORKER, process.getWorkerId().orElseThrow());
        assertEquals(now, process.getNextRunAt().orElseThrow());
        dao.insert(process);

        process.changeState(State.WAIT);
        assertEquals(WorkerId.DEFAULT, process.getWorkerId().orElseThrow());
        assertEquals(now.plusSeconds(2), process.getAfterDueAt().orElseThrow());
        assertTrue(process.getEveryDueAt().isEmpty());
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "payload",
                now.plusSeconds(1));
        assertEquals(now.plusSeconds(1), process.getNextRunAt().orElseThrow());
        dao.update(process);
        var restored = dao.findById(process.getId()).orElseThrow();
        assertEquals("payload",
                restored.getMessages().convert(restored.getMessages().peekFirst().orElseThrow(), SIGNAL).payload());

        clock.setTime(now.plusSeconds(3));
        restored.changeState(State.WAIT);
        assertFalse(restored.getMessages().isEmpty());
        assertEquals(now.plusSeconds(2), restored.getAfterDueAt().orElseThrow());
        assertEquals(now.plusSeconds(1), restored.getNextRunAt().orElseThrow());
        clock.setTime(now.plusSeconds(4));
        restored.changeState(State.DONE);
        assertEquals(ProcessStatus.COMPLETED, restored.getStatus());
        assertTrue(restored.getWorkerId().isEmpty());
        assertTrue(restored.getNextRunAt().isEmpty());
        assertTrue(restored.getAfterDueAt().isEmpty());
        dao.update(restored);
        assertEquals(ProcessStatus.COMPLETED, dao.findById(process.getId()).orElseThrow().getStatus());
    }

    @Test
    void timerChangesPreserveDelayedStepStart() {
        var now = clock.instant();
        var startAt = now.plusSeconds(60);
        var process = factory().newInstance(TYPE, startAt, null, "routing", Map.of(), Map.of());
        clock.fastForwardTime(Duration.ofSeconds(1));

        process.setTimer(DUE, now);
        assertEquals(startAt, process.getNextRunAt().orElseThrow());
        assertTrue(process.cancelTimer(DUE));
        assertFalse(process.cancelTimer(DUE));
        assertEquals(startAt, process.getNextRunAt().orElseThrow());
        assertFalse(process.isTimeToExecute());

        process.changeState(State.START);
        assertEquals(clock.instant(), process.getNextRunAt().orElseThrow());
        assertTrue(process.isTimeToExecute());
        assertEquals(1, process.drainPendingHistoryEntries().size());
    }

    @Test
    void messagesAndTimersPreserveExpiredRetryUntilStateIsReactivated() {
        var now = clock.instant();
        var process = factory().newInstance(TYPE, now, null, "routing", Map.of(), Map.of());
        process.changeState(State.WAIT);
        process.scheduleRetryAfter(Duration.ofSeconds(10), "retry");
        clock.fastForwardTime(Duration.ofSeconds(20));

        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "payload", clock.instant());
        process.setTimer(DUE, now.plusSeconds(1));
        assertEquals(now.plusSeconds(10), process.getNextRunAt().orElseThrow());
        assertTrue(process.cancelTimer(DUE));
        assertEquals(now.plusSeconds(10), process.getNextRunAt().orElseThrow());
        assertEquals(1, process.getFailedAttempts());
        assertEquals("retry", process.getLastError().orElseThrow());

        process.markFailed("failed");
        process.setTimer(DUE, now.plusSeconds(1));
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "another", clock.instant());
        assertTrue(process.getNextRunAt().isEmpty());

        assertTrue(process.resume());
        assertEquals(ProcessStatus.ACTIVE, process.getStatus());
        assertEquals(now.plusSeconds(1), process.getNextRunAt().orElseThrow());
        assertEquals(now.plusSeconds(2), process.getAfterDueAt().orElseThrow());
        assertEquals(0, process.getFailedAttempts());
        assertTrue(process.getLastError().isEmpty());
        assertFalse(process.getMessages().isEmpty());
        assertEquals(2, process.drainPendingHistoryEntries().size());
    }

    @Test
    void resumingFailedStepRunsItImmediately() {
        var process = factory().newInstance(TYPE, clock.instant(), null, "routing", Map.of(), Map.of());
        process.markFailed("failed");
        clock.fastForwardTime(Duration.ofSeconds(10));
        process.setTimer(DUE, clock.instant());
        assertTrue(process.getNextRunAt().isEmpty());

        assertTrue(process.resume());
        assertEquals(ProcessStatus.ACTIVE, process.getStatus());
        assertEquals(clock.instant(), process.getNextRunAt().orElseThrow());
        assertEquals(0, process.getFailedAttempts());
        assertTrue(process.getLastError().isEmpty());
        assertFalse(process.resume());
        assertEquals(1, process.drainPendingHistoryEntries().size());
    }

    private ProcessInstanceFactory factory() {
        ProcessDefinition definition = builder -> builder.type(TYPE)
            .defaultWorker(ACTION_WORKER)
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL,
                    (context, message) -> {}, State.WAIT)
                .after(Duration.ofSeconds(2), State.DONE)
                .at(DUE, State.DONE))
            .terminal(State.DONE)
            .build();
        return new ProcessInstanceFactory(clock,
                new ProcessDefinitionRegistry(List.of(definition)));
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        DONE
    }
}
