package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProcessTimePrecisionTest extends BaseTest {
    private static final Instant START = Instant.parse("2026-11-10T10:00:00Z");
    private static final ProcessType TYPE = ProcessType.of("PRECISION");
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private static final TimerType DUE = TimerType.of("due");
    private final MutableClock clock = new MutableClock(ZoneOffset.UTC, START, ChronoUnit.NANOS);
    private ProcessInstanceFactory factory;
    private ProcessInstanceDao dao;

    @BeforeEach
    void setUpPrecisionTest() {
        factory = new ProcessInstanceFactory(clock, new ProcessDefinitionRegistry(List.of(new Definition())));
        dao = new ProcessInstanceDao(database, clock, factory, false, new ProcessEngineMetrics() {
        });
    }

    @Test
    void deadlinesRoundUpAfterAddingDelaysAndSurviveReload() {
        clock.setTime(START.plusNanos(100));
        var process = newProcess();
        assertEquals(Optional.of(START.plusNanos(1000)), process.getNextRunAt());
        process.enterState(State.WAIT);
        assertEquals(Optional.of(START.plusNanos(1000)), process.getEveryDueAt());
        assertEquals(Optional.of(START.plusNanos(1000)), process.getAfterDueAt());
        assertFalse(process.isTimeToExecute());
        assertTrue(process.dueAwaitEvent().isEmpty());

        dao.insert(process);
        var restored = dao.findById(process.getId()).orElseThrow();
        assertEquals(process.getNextRunAt(), restored.getNextRunAt());
        assertEquals(process.getEveryDueAt(), restored.getEveryDueAt());
        assertEquals(process.getAfterDueAt(), restored.getAfterDueAt());
        assertTrue(restored.dueAwaitEvent().isEmpty());
        clock.setTime(START.plusNanos(1000));
        assertEquals(Optional.of(ProcessInstance.AwaitEvent.EVERY), restored.dueAwaitEvent());
    }

    @Test
    void messageAndTimersKeepTheirOrderAcrossReload() {
        var process = newProcess();
        process.enterState(State.WAIT);
        process.setTimer(DUE, START.plusNanos(900));
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "message", START.plusNanos(300));
        var due = START.plusNanos(1000);
        assertEquals(Optional.of(due), process.getTimer(DUE));
        assertEquals(Optional.of(due), process.getMessages().firstReceivedAt());
        assertTrue(process.dueAwaitEvent().isEmpty());
        clock.setTime(due);
        assertEquals(Optional.of(ProcessInstance.AwaitEvent.MESSAGE), process.dueAwaitEvent());

        dao.insert(process);
        var restored = dao.findById(process.getId()).orElseThrow();
        assertEquals(process.dueAwaitEvent(), restored.dueAwaitEvent());
        assertEquals(process.getNextRunAt(), restored.getNextRunAt());
        assertEquals(process.getTimers().asMap(), restored.getTimers().asMap());
        var message = restored.getMessages().convert(restored.getMessages().takeFirst(), SIGNAL);
        assertEquals(due, message.receivedAt());
        assertEquals(Optional.of(ProcessInstance.AwaitEvent.EVERY), restored.dueAwaitEvent());
        restored.advanceEvery();
        assertEquals(Optional.of(ProcessInstance.AwaitEvent.AFTER), restored.dueAwaitEvent());
        restored.disableAfter();
        assertEquals(Optional.of(ProcessInstance.AwaitEvent.AT), restored.dueAwaitEvent());
    }

    @Test
    void roundedEveryPeriodKeepsAdvancingOnTheSameGridAcrossReloads() {
        var process = newProcess();
        process.enterState(State.WAIT);
        process.disableAfter();
        process.stayInAwait();
        dao.insert(process);
        for (int i = 0; i < 3; i++) {
            var due = START.plusNanos(1000 + i * 2000);
            clock.setTime(due);
            process = dao.findById(process.getId()).orElseThrow();
            assertEquals(Optional.of(due), process.getEveryDueAt());
            assertEquals(Optional.of(ProcessInstance.AwaitEvent.EVERY), process.dueAwaitEvent());
            process.advanceEvery();
            process.enterState(State.WAIT);
            assertEquals(Optional.of(due.plusNanos(2000)), process.getEveryDueAt());
            assertTrue(process.dueAwaitEvent().isEmpty());
            dao.update(process);
        }
    }

    @Test
    void retryRoundsUpWithoutEarlyExecutionAndKeepsZeroDelay() {
        var process = newProcess();
        process.scheduleRetryAfter(Duration.ofNanos(1), "failure");
        assertEquals(Optional.of(START.plusNanos(1000)), process.getNextRunAt());
        assertFalse(process.isTimeToExecute());
        dao.insert(process);
        var restored = dao.findById(process.getId()).orElseThrow();
        assertEquals(process.getNextRunAt(), restored.getNextRunAt());
        assertFalse(restored.isTimeToExecute());
        clock.setTime(START.plusNanos(1000));
        assertTrue(restored.isTimeToExecute());
        restored.scheduleRetryAfter(Duration.ZERO, "failure");
        dao.update(restored);
        assertTrue(dao.findById(process.getId()).orElseThrow().isTimeToExecute());
    }

    @Test
    void intervalRoundsPositivePeriodsUpAndKeepsZeroFirstDelay() {
        assertEquals(Duration.ofNanos(1000), Interval.of(Duration.ofNanos(1)).period());
        assertEquals(Duration.ofNanos(2000), Interval.of(Duration.ofNanos(1500)).period());
        assertEquals(Duration.ofSeconds(1), Interval.of(Duration.ofSeconds(1)).period());
        assertEquals(Duration.ZERO, Interval.of(Duration.ofNanos(1)).firstAfter(Duration.ZERO).firstAfter());
        assertThrows(IllegalArgumentException.class, () -> Interval.of(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Interval.of(Duration.ofNanos(-1)));
    }

    private ProcessInstance newProcess() {
        return factory.newInstance(TYPE, clock.instant(), null, "precision", Map.of(), Map.of());
    }

    private static final class Definition implements ProcessDefinition {
        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder.type(TYPE)
                .initialState(State.START)
                .step(State.START, Definition::onTimer, State.WAIT)
                .await(State.WAIT, Definition::configureAwait)
                .terminal(State.DONE)
                .build();
        }

        private static void configureAwait(ProcessSpecBuilder.AwaitBuilder await) {
            await.every(new Interval(Duration.ofNanos(1500), Duration.ofNanos(1)), Definition::onTimer, State.WAIT)
                .after(Duration.ofNanos(400), State.DONE)
                .at(DUE, State.DONE)
                .message(SIGNAL, Definition::onMessage, State.DONE);
        }

        private static void onTimer(ProcessContext context) {
        }

        private static void onMessage(ProcessContext context, IncomingMessage<String> message) {
        }
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        DONE
    }
}
