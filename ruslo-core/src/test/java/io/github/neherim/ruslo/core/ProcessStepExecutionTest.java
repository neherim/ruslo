package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.RusloTimeSource;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.exception.UnsupportedProcessStateException;
import io.github.neherim.ruslo.core.api.test.MutableTimeSource;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProcessStepExecutionTest extends BaseTest {
    private static final Instant ENGINE_TIME = Instant.parse("2026-11-18T07:20:57.645772Z");
    private static final RusloTimeSource SUB_MICROSECOND_SOURCE = () -> ENGINE_TIME.plusNanos(600);
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private static final VariableType<String> PAYLOAD = VariableType.of("payload", String.class);
    private static final VariableType<String> TEMPORARY = VariableType.of("temporary", String.class);

    @Test
    void actionAwaitMessageActionTerminalUsesDistinctTransactions() {
        var handled = new AtomicInteger();
        var actionRan = new AtomicInteger();
        ProcessDefinition definition = builder -> builder.type("FLOW")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                handled.incrementAndGet();
                context.setVariable(PAYLOAD, message.payload());
            }, State.AFTER))
            .step(State.AFTER, context -> {
                actionRan.incrementAndGet();
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("FLOW")).start();
            assertThrows(MessageNotExpectedException.class, () -> engine.sendMessage(id, SIGNAL, "early"));
            assertThrows(MessageNotExpectedException.class,
                    () -> new ProcessEngineAdmin(engine).sendMessage(id, SIGNAL.name(), "early"));
            emulator(engine).runUntilStop(id);
            assertEquals(State.WAIT.name(), engine.processes().byId(id).singleDetails().orElseThrow().state().name());
            assertTrue(engine.processes().byId(id).singleDetails().orElseThrow().nextRunAt().isEmpty());
            engine.sendMessage(id, SIGNAL, "accepted");
            assertEquals(0, handled.get());
            assertTrue(engine.processes().byId(id).singleDetails().orElseThrow().nextRunAt().isPresent());
            emulator(engine).runOneStep(id);
            assertEquals(1, handled.get());
            assertEquals(0, actionRan.get());
            assertEquals(State.AFTER.name(), engine.processes().byId(id).singleDetails().orElseThrow().state().name());
            emulator(engine).runOneStep(id);
            var completed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.COMPLETED, completed.status());
            assertEquals(Optional.of("accepted"), completed.variables().find(PAYLOAD));
        } finally {
            engine.close();
        }
    }

    @Test
    void queuedMessageWithoutHandlerCompletesProcess() {
        var type = ProcessType.of("MESSAGE_TRANSITION");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, State.DONE))
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(definition)) {
            var id = engine.newProcess(type).start();

            engine.sendMessage(id, SIGNAL, "go");

            assertEquals(State.WAIT, engine.processes().byId(id).singleDetails().orElseThrow().state());
            var completed = emulator(engine).runOneStep(id);
            assertEquals(State.DONE, completed.state());
            assertEquals(ProcessStatus.COMPLETED, completed.status());
        }
    }

    @Test
    void runUntilStopPassesCommittedTransitionsUntilAwaitAndTerminal() {
        ProcessDefinition definition = builder -> builder.type("CHAIN")
            .initialState(State.START)
            .step(State.START, context -> {}, State.AFTER)
            .step(State.AFTER, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                assertEquals(ENGINE_TIME, message.receivedAt());
            }, State.DONE))
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(SUB_MICROSECOND_SOURCE, definition)) {
            var emulator = emulator(engine);
            var id = engine.newProcess(ProcessType.of("CHAIN")).start();
            assertEquals(State.WAIT.name(), emulator.runUntilStop(id).state().name());
            var completed = emulator.sendMessageAndRunUntilStop(id, SIGNAL, "go");
            assertEquals(ProcessStatus.COMPLETED, completed.status());
        }
    }

    @Test
    void zeroDelayMessageRetryIsReadyWithSubMicrosecondClock() {
        var fail = new AtomicBoolean(true);
        ProcessDefinition definition = builder -> builder.type("MESSAGE_RETRY")
            .onError(ProcessErrorHandler.fixed(1, Duration.ZERO))
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                if (fail.getAndSet(false)) throw new IllegalStateException("boom");
            }, State.DONE))
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(SUB_MICROSECOND_SOURCE, definition)) {
            var id = engine.newProcess(ProcessType.of("MESSAGE_RETRY")).start();
            engine.sendMessage(id, SIGNAL, "go");

            var emulator = emulator(engine);
            assertEquals(1, emulator.runOneStep(id).attempt());
            assertEquals(ProcessStatus.COMPLETED, emulator.runUntilStop(id).status());
        }
    }

    @Test
    void zeroDelayFirstTimerIsReadyWithSubMicrosecondClock() {
        ProcessDefinition definition = builder -> builder.type("IMMEDIATE_TIMER")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.every(Interval.of(Duration.ofMinutes(1)).firstAfter(Duration.ZERO),
                    context -> {}, State.DONE))
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(SUB_MICROSECOND_SOURCE, definition)) {
            var id = engine.newProcess(ProcessType.of("IMMEDIATE_TIMER")).start();

            assertEquals(ProcessStatus.COMPLETED, emulator(engine).runUntilStop(id).status());
        }
    }

    @Test
    void selfTargetConsumesOneMessageAndPreservesNext() {
        var handled = new AtomicInteger();
        ProcessDefinition definition = builder -> builder.type("KEEP")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                handled.incrementAndGet();
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("KEEP")).start();
            emulator(engine).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "one");
            engine.sendMessage(id, SIGNAL, "two");
            emulator(engine).runOneStep(id);
            assertEquals(1, handled.get());
            emulator(engine).runOneStep(id);
            assertEquals(2, handled.get());
        } finally {
            engine.close();
        }
    }

    @Test
    void queuedMessagesSurviveEngineRestartInFifoOrder() {
        var received = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("RESTART_QUEUE")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                received.add(message.payload());
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var firstEngine = newProcessEngineWith(definition);
        var id = firstEngine.newProcess(ProcessType.of("RESTART_QUEUE")).start();
        emulator(firstEngine).runOneStep(id);
        firstEngine.sendMessage(id, SIGNAL, "first");
        firstEngine.sendMessage(id, SIGNAL, "second");
        firstEngine.close();

        var restartedEngine = newProcessEngineWith(definition);
        try {
            emulator(restartedEngine).runOneStep(id);
            emulator(restartedEngine).runOneStep(id);
            assertEquals(List.of("first", "second"), received);
            assertEquals(ProcessStatus.ACTIVE,
                    restartedEngine.processes().byId(id).singleDetails().orElseThrow().status());
        } finally {
            restartedEngine.close();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queuedMessagesKeepNestedDecimalValuesAcrossQueueUpdates(boolean administrative) {
        record Payment(BigDecimal amount, List<BigDecimal> parts) {
        }
        var type = MessageType.of("PAYMENT", Payment.class);
        var received = new ArrayList<Payment>();
        var payments = List.of(
                new Payment(new BigDecimal("9.99999999999999999999999"), List.of(new BigDecimal("1.10"))),
                new Payment(new BigDecimal("2.00"), List.of(new BigDecimal("0.123456789012345678901234567890"))));
        ProcessDefinition definition = builder -> builder.type("DECIMAL_QUEUE")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(type, (context, message) -> {
                received.add(message.payload());
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();

        try (var engine = newProcessEngineWith(definition)) {
            var id = engine.newProcess(ProcessType.of("DECIMAL_QUEUE")).start();
            for (var payment : payments) {
                if (administrative) {
                    new ProcessEngineAdmin(engine).sendMessage(id, type.name(),
                            Map.of("amount", payment.amount(), "parts", payment.parts()));
                } else {
                    engine.sendMessage(id, type, payment);
                }
            }

            emulator(engine).runUntilStop(id);

            assertEquals(payments, received);
        }
    }

    @Test
    void timerRunsOnlyWhenDue() {
        var clock = new MutableTimeSource(Instant.parse("2026-09-24T10:00:00Z"));
        ProcessDefinition definition = builder -> builder.type("TIMER")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.after(Duration.ofSeconds(5), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withTimeSource(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("TIMER")).start();
            emulator(engine, 20).runOneStep(id);
            assertEquals(ProcessStatus.ACTIVE, emulator(engine, 20).runOneStep(id).status());
            clock.fastForwardTime(Duration.ofSeconds(5));
            assertEquals(ProcessStatus.COMPLETED, emulator(engine, 20).runOneStep(id).status());
        } finally {
            engine.close();
        }
    }

    @Test
    void failedMessageHandlerRollsBackAndResumeReplaysTheSameMessage() {
        var fail = new AtomicBoolean(true);
        var received = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("ROLLBACK_MESSAGE")
            .onError((context, error) -> ErrorResult.fail())
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                received.add(message.payload());
                context.setVariable(TEMPORARY, "value");
                if (fail.get()) throw new IllegalStateException("handler failed");
            }, State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("ROLLBACK_MESSAGE")).start();
            emulator(engine).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "first");
            emulator(engine).runOneStep(id);
            var failed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertTrue(failed.variables().find(TEMPORARY).isEmpty());
            fail.set(false);
            engine.resumeFailedProcess(id);
            var active = assertThrows(ProcessStatusException.class, () -> engine.resumeFailedProcess(id),
                    "an active process is not resumed again");
            assertEquals(ProcessStatus.ACTIVE, active.getStatus());
            emulator(engine).runOneStep(id);
            assertEquals(List.of("first", "first"), received);
            assertEquals(ProcessStatus.COMPLETED, engine.processes().byId(id).singleDetails().orElseThrow().status());
            var completed = assertThrows(ProcessStatusException.class, () -> engine.resumeFailedProcess(id),
                    "a completed process is not resumed");
            assertEquals(ProcessStatus.COMPLETED, completed.getStatus());
        } finally {
            engine.close();
        }
    }

    @Test
    void resumesFailedAwaitAtAnotherStep() {
        var type = ProcessType.of("MANUAL_CANCEL");
        var handlerCalls = new AtomicInteger();
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                handlerCalls.incrementAndGet();
                throw new IllegalStateException("handler failed");
            }, State.DONE))
            .step(State.CANCEL, context -> context.setVariable(PAYLOAD, context.getVariable(PAYLOAD) + " cancelled"),
                    State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(definition)) {
            var emulator = emulator(engine);
            var id = engine.newProcess(type).variable(PAYLOAD, "order").start();
            var active = assertThrows(ProcessStatusException.class, () -> engine.resumeFailedProcess(id, State.CANCEL));
            assertEquals(ProcessStatus.ACTIVE, active.getStatus());

            engine.sendMessage(id, SIGNAL, "fail");
            var failed = emulator.runOneStep(id);
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertEquals(1, failed.attempt());
            assertTrue(failed.lastError().isPresent());
            assertThrows(UnsupportedProcessStateException.class, () -> engine.resumeFailedProcess(id, State.AFTER));
            assertEquals(failed, engine.processes().byId(id).singleDetails().orElseThrow());

            engine.resumeFailedProcess(id, State.CANCEL);
            var resumed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.ACTIVE, resumed.status());
            assertEquals(State.CANCEL, resumed.state());
            assertEquals(0, resumed.attempt());
            assertTrue(resumed.lastError().isEmpty());
            assertEquals(List.of(State.WAIT, State.CANCEL),
                    resumed.stateHistory().stream().map(entry -> entry.state()).toList());

            var completed = emulator.runUntilStop(id);
            assertEquals(ProcessStatus.COMPLETED, completed.status());
            assertEquals(Optional.of("order cancelled"), completed.variables().find(PAYLOAD));
            assertEquals(1, handlerCalls.get());
            var completedRejection = assertThrows(ProcessStatusException.class,
                    () -> engine.resumeFailedProcess(id, State.CANCEL));
            assertEquals(ProcessStatus.COMPLETED, completedRejection.getStatus());
        }
    }

    @Test
    void resumesFailedStepAtAnAwait() {
        var type = ProcessType.of("RESUME_AT_AWAIT");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(State.START)
            .step(State.START, context -> {
                throw new IllegalStateException("step failed");
            }, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                context.setVariable(PAYLOAD, message.payload());
            }, State.DONE))
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(definition)) {
            var emulator = emulator(engine);
            var id = engine.newProcess(type).start();
            assertEquals(ProcessStatus.FAILED, emulator.runUntilStop(id).status());

            engine.resumeFailedProcess(id, State.WAIT);
            var waiting = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(State.WAIT, waiting.state());
            assertEquals(ProcessStatus.ACTIVE, waiting.status());
            assertTrue(waiting.nextRunAt().isEmpty());
            assertEquals(0, waiting.attempt());
            assertTrue(waiting.lastError().isEmpty());

            var completed = emulator.sendMessageAndRunUntilStop(id, SIGNAL, "accepted");
            assertEquals(ProcessStatus.COMPLETED, completed.status());
            assertEquals(Optional.of("accepted"), completed.variables().find(PAYLOAD));
        }
    }

    @Test
    void completesFailedProcessAtATerminalState() {
        var type = ProcessType.of("RESUME_AT_TERMINAL");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(State.START)
            .step(State.START, context -> {
                throw new IllegalStateException("step failed");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngineWith(definition)) {
            var id = engine.newProcess(type).start();
            assertEquals(ProcessStatus.FAILED, emulator(engine).runUntilStop(id).status());

            engine.resumeFailedProcess(id, State.DONE);
            var completed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(State.DONE, completed.state());
            assertEquals(ProcessStatus.COMPLETED, completed.status());
            assertTrue(completed.nextRunAt().isEmpty());
            assertEquals(0, completed.attempt());
            assertTrue(completed.lastError().isEmpty());
            assertEquals(List.of(State.START, State.DONE),
                    completed.stateHistory().stream().map(entry -> entry.state()).toList());
        }
    }

    @Test
    void rejectedMessageNamesAwaitedMessages() {
        var reply = MessageType.of("REPLY", String.class);
        ProcessDefinition definition = builder -> builder.type("TWO_AWAITS")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {}, State.AFTER))
            .await(State.AFTER, await -> await.message(reply, (context, message) -> {}, State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("TWO_AWAITS")).start();

            var rejected = assertThrows(MessageNotExpectedException.class, () -> engine.sendMessage(id, reply, "r"));

            assertEquals("Message REPLY is not expected for ACTIVE process " + id
                    + " in state WAIT: the state awaits only [SIGNAL]", rejected.getMessage());
        } finally {
            engine.close();
        }
    }

    @Test
    void failedProcessQueuesMessagesAndRejectsSynchronousExecutionUntilResume() {
        var fail = new AtomicBoolean(true);
        var received = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("FAILED_QUEUE")
            .onError((context, error) -> ErrorResult.fail())
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                received.add(message.payload());
                if (fail.get()) throw new IllegalStateException("handler failed");
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("FAILED_QUEUE")).start();
            emulator(engine).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "first");
            emulator(engine).runOneStep(id);
            assertEquals(ProcessStatus.FAILED, engine.processes().byId(id).singleDetails().orElseThrow().status());

            engine.sendMessage(id, SIGNAL, "later");
            new ProcessEngineAdmin(engine).sendMessage(id, SIGNAL.name(), "admin");
            assertEquals(ProcessStatus.FAILED, engine.processes().byId(id).singleDetails().orElseThrow().status());
            assertThrows(ProcessStatusException.class, () -> emulator(engine).runOneStep(id));
            var rejected = assertThrows(MessageNotExpectedException.class,
                    () -> engine.sendMessageSync(id, SIGNAL, "sync"));
            assertEquals(ProcessStatus.FAILED, rejected.getStatus());
            assertEquals(Set.of(SIGNAL.name()), rejected.getExpectedMessageNames());
            assertEquals("Message SIGNAL is not expected for FAILED process " + id + " in state WAIT: a FAILED "
                    + "process cannot handle a message synchronously; resume it or queue the message with sendMessage",
                    rejected.getMessage());
            assertThrows(IllegalArgumentException.class,
                    () -> engine.sendMessage(id, MessageType.of("OTHER", String.class), "other"));
            assertEquals(List.of("first"), received);

            fail.set(false);
            engine.resumeFailedProcess(id);
            emulator(engine).runUntilStop(id);
            assertEquals(List.of("first", "first", "later", "admin"), received);
            assertEquals(ProcessStatus.ACTIVE, engine.processes().byId(id).singleDetails().orElseThrow().status());
        } finally {
            engine.close();
        }
    }

    @Test
    void retryBackoffRetainsFirstMessageWhenAnotherArrives() {
        var clock = new MutableTimeSource(Instant.parse("2026-09-24T10:00:00Z"));
        var fail = new AtomicBoolean(true);
        var received = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("BACKOFF_MESSAGE")
            .onError((context, error) -> ErrorResult.retryAfter(Duration.ofSeconds(10)))
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                received.add(message.payload());
                if (fail.get()) throw new IllegalStateException("retry");
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withTimeSource(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("BACKOFF_MESSAGE")).start();
            emulator(engine, 20).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "first");
            emulator(engine, 20).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "second");
            fail.set(false);
            emulator(engine, 20).runOneStep(id);
            assertEquals(List.of("first"), received);
            clock.fastForwardTime(Duration.ofSeconds(10));
            emulator(engine, 20).runOneStep(id);
            emulator(engine, 20).runOneStep(id);
            assertEquals(List.of("first", "first", "second"), received);
        } finally {
            engine.close();
        }
    }

    @Test
    void awaitChoosesEarlierEventAndMessageWinsTie() {
        assertEquals("message", firstEventAt(Duration.ofSeconds(1), Duration.ofSeconds(6)));
        assertEquals("timer", firstEventAt(Duration.ofSeconds(6), Duration.ofSeconds(6)));
        assertEquals("message", firstEventAt(Duration.ofSeconds(5), Duration.ofSeconds(5)));
    }

    private String firstEventAt(Duration messageDelay, Duration executeAfter) {
        var clock = new MutableTimeSource(Instant.parse("2026-09-24T10:00:00Z"));
        var selected = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("EVENT_ORDER")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await
                .message(SIGNAL, (context, message) -> selected.add("message"), State.WAIT)
                .every(Duration.ofSeconds(5), context -> selected.add("timer"), State.WAIT)
                .after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withTimeSource(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("EVENT_ORDER")).start();
            emulator(engine, 20).runOneStep(id);
            clock.fastForwardTime(messageDelay);
            engine.sendMessage(id, SIGNAL, "value");
            clock.fastForwardTime(executeAfter.minus(messageDelay));
            emulator(engine, 20).runOneStep(id);
            return selected.getFirst();
        } finally {
            engine.close();
        }
    }

    @Test
    void concurrentSendersDoNotLoseSignals() throws Exception {
        var handled = java.util.Collections.synchronizedList(new ArrayList<String>());
        ProcessDefinition definition = builder -> builder.type("CONCURRENT_SENDERS")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                handled.add(message.payload());
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try (var senders = Executors.newFixedThreadPool(2)) {
            var id = engine.newProcess(ProcessType.of("CONCURRENT_SENDERS")).start();
            emulator(engine).runOneStep(id);
            var start = new CountDownLatch(1);
            var first = senders.submit(() -> {
                start.await();
                engine.sendMessage(id, SIGNAL, "one");
                return null;
            });
            var second = senders.submit(() -> {
                start.await();
                engine.sendMessage(id, SIGNAL, "two");
                return null;
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            emulator(engine).runOneStep(id);
            emulator(engine).runOneStep(id);
            assertEquals(2, handled.size());
            assertTrue(handled.containsAll(List.of("one", "two")));
        } finally {
            engine.close();
        }
    }

    @Test
    void senderWaitsForTransactionalHandlerAndSeesClosedAwait() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ProcessDefinition definition = builder -> builder.type("SENDER_HANDLER_RACE")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ex);
                }
            }, State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var id = engine.newProcess(ProcessType.of("SENDER_HANDLER_RACE")).start();
            emulator(engine).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "first");
            var handler = workers.submit(() -> emulator(engine).runOneStep(id));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var senderStarted = new CountDownLatch(1);
            var sender = workers.submit(() -> {
                senderStarted.countDown();
                engine.sendMessage(id, SIGNAL, "late");
            });
            assertTrue(senderStarted.await(5, TimeUnit.SECONDS));
            assertFalse(sender.isDone());
            release.countDown();
            assertEquals(ProcessStatus.COMPLETED, handler.get(5, TimeUnit.SECONDS).status());
            var rejected = assertThrows(ExecutionException.class, () -> sender.get(5, TimeUnit.SECONDS));
            assertInstanceOf(MessageNotExpectedException.class, rejected.getCause());
            assertEquals(ProcessStatus.COMPLETED, engine.processes().byId(id).singleDetails().orElseThrow().status());
        } finally {
            release.countDown();
            engine.close();
        }
    }

    @Test
    void reenteringAwaitDiscardsSignalsFromThePreviousVisit() {
        var handled = new AtomicInteger();
        ProcessDefinition definition = builder -> builder.type("REENTER_AWAIT")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                handled.incrementAndGet();
            }, State.AFTER))
            .step(State.AFTER, context -> {}, State.WAIT)
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("REENTER_AWAIT")).start();
            emulator(engine).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "first");
            engine.sendMessage(id, SIGNAL, "obsolete");
            emulator(engine).runOneStep(id);
            emulator(engine).runOneStep(id);
            emulator(engine).runOneStep(id);
            assertEquals(1, handled.get());
            engine.sendMessage(id, SIGNAL, "new");
            emulator(engine).runOneStep(id);
            assertEquals(2, handled.get());
        } finally {
            engine.close();
        }
    }

    @Test
    void deliveryChecksNameAndPayloadClass() {
        var received = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("DELIVERY_TYPES")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                received.add(message.payload());
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("DELIVERY_TYPES")).start();
            emulator(engine).runOneStep(id);
            assertThrows(IllegalArgumentException.class,
                    () -> engine.sendMessage(id, MessageType.of("OTHER", String.class), "wrong"));
            assertThrows(IllegalArgumentException.class,
                    () -> engine.sendMessage(id, MessageType.of("SIGNAL", Integer.class), 1));
            engine.sendMessage(id, SIGNAL, null);
            new ProcessEngineAdmin(engine).sendMessage(id, SIGNAL.name(), null);
            emulator(engine).runOneStep(id);
            emulator(engine).runOneStep(id);
            assertEquals(2, received.size());
            assertNull(received.getFirst());
            assertNull(received.getLast());
        } finally {
            engine.close();
        }
    }

    @Test
    void timerSelfTargetKeepsGridAndFailureKeepsDueTimer() {
        var clock = new MutableTimeSource(Instant.parse("2026-09-24T10:00:00Z"));
        var fail = new AtomicBoolean(true);
        var handled = new AtomicInteger();
        ProcessDefinition definition = builder -> builder.type("TIMER_RETRY")
            .onError((context, error) -> ErrorResult.fail())
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.every(Duration.ofSeconds(5), context -> {
                handled.incrementAndGet();
                if (fail.get()) throw new IllegalStateException("timer failed");
            }, State.WAIT).after(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withTimeSource(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("TIMER_RETRY")).start();
            emulator(engine, 20).runOneStep(id);
            var firstDue = engine.processes().byId(id).singleDetails().orElseThrow().nextRunAt().orElseThrow();
            clock.fastForwardTime(Duration.ofSeconds(8));
            emulator(engine, 20).runOneStep(id);
            var failed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertTrue(failed.nextRunAt().isEmpty());
            fail.set(false);
            engine.resumeFailedProcess(id);
            emulator(engine, 20).runOneStep(id);
            assertEquals(2, handled.get());
            assertEquals(firstDue.plusSeconds(5),
                    engine.processes().byId(id).singleDetails().orElseThrow().nextRunAt().orElseThrow());
        } finally {
            engine.close();
        }
    }

    @Test
    void branchingStepAndMessageFollowRoutes() {
        ProcessDefinition definition = builder -> builder.type("ROUTES")
            .initialState(State.START)
            .step(State.START, context -> Outcome.SECOND, routes -> routes
                .on(Outcome.FIRST, State.DONE)
                .on(Outcome.SECOND, State.WAIT))
            .await(State.WAIT, await -> await.message(SIGNAL,
                    (context, message) -> message.payload().equals("go") ? Outcome.FIRST : Outcome.SECOND,
                    routes -> routes.on(Outcome.FIRST, State.AFTER).on(Outcome.SECOND, State.WAIT)))
            .step(State.AFTER, context -> {}, State.DONE)
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("ROUTES")).start();
            assertEquals(State.WAIT.name(), emulator(engine).runUntilStop(id).state().name());
            engine.sendMessage(id, SIGNAL, "wait");
            assertEquals(State.WAIT.name(), emulator(engine).runUntilStop(id).state().name());
            assertEquals(ProcessStatus.COMPLETED,
                    emulator(engine).sendMessageAndRunUntilStop(id, SIGNAL, "go").status());
        } finally {
            engine.close();
        }
    }

    @Test
    void nullOutcomeFailsWithStateAndProcessTypeInLastError() {
        ProcessDefinition definition = builder -> builder.type("MISMATCH")
            .initialState(State.START)
            .step(State.START, context -> (Outcome) null, routes -> routes
                .on(Outcome.FIRST, State.DONE)
                .on(Outcome.SECOND, State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("MISMATCH")).start();
            var failed = emulator(engine).runUntilStop(id);
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertTrue(
                    failed.lastError().orElseThrow().contains("Branching handler in state START of process MISMATCH"),
                    failed.lastError().orElseThrow());
            assertTrue(failed.lastError().orElseThrow().contains("returned null"), failed.lastError().orElseThrow());
        } finally {
            engine.close();
        }
        var other = newProcessEngineWith(builder -> builder.type("MISMATCH_AWAIT")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> (Outcome) null, routes -> routes
                .on(Outcome.FIRST, State.DONE)
                .on(Outcome.SECOND, State.WAIT)))
            .terminal(State.DONE)
            .build());
        try {
            var id = other.newProcess(ProcessType.of("MISMATCH_AWAIT")).start();
            var failed = emulator(other).sendMessageAndRunUntilStop(id, SIGNAL, "x");
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertTrue(
                    failed.lastError()
                        .orElseThrow()
                        .contains("Branching handler in state WAIT of process MISMATCH_AWAIT"),
                    failed.lastError().orElseThrow());
        } finally {
            other.close();
        }
    }

    @Test
    void awaitSelfTargetKeepsWaitingAndStepRestartsWaiting() {
        var clock = new MutableTimeSource(Instant.parse("2026-09-24T10:00:00Z"));
        var handled = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("SELF")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await
                .message(SIGNAL, (context, message) -> {
                    handled.add(message.payload());
                    return message.payload().equals("restart") ? Outcome.FIRST : Outcome.SECOND;
                }, routes -> routes.on(Outcome.FIRST, State.AFTER).on(Outcome.SECOND, State.WAIT))
                .after(Duration.ofSeconds(50), State.DONE))
            .step(State.AFTER, context -> {}, State.WAIT)
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withTimeSource(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("SELF")).start();
            clock.fastForwardTime(Duration.ofSeconds(10));
            engine.sendMessage(id, SIGNAL, "keep");
            engine.sendMessage(id, SIGNAL, "restart");
            engine.sendMessage(id, SIGNAL, "obsolete");
            var kept = emulator(engine, 20).runOneStep(id);
            assertEquals(1, kept.stateHistory().stream().filter(entry -> entry.state() == State.WAIT).count());
            emulator(engine, 20).runOneStep(id);
            emulator(engine, 20).runOneStep(id);
            var reentered = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(2, reentered.stateHistory().stream().filter(entry -> entry.state() == State.WAIT).count());
            assertEquals(List.of("keep", "restart"), handled);
            clock.fastForwardTime(Duration.ofSeconds(49));
            assertEquals(State.WAIT.name(), emulator(engine, 20).runOneStep(id).state().name());
            clock.fastForwardTime(Duration.ofSeconds(1));
            assertEquals(ProcessStatus.COMPLETED, emulator(engine, 20).runOneStep(id).status());
        } finally {
            engine.close();
        }
    }

    @Test
    void stepSelfTargetAndMoveToCurrentStepRunStepAgainWithoutHistoryRow() {
        var runs = new AtomicInteger();
        BranchingAction<Outcome> step = context -> {
            var run = runs.incrementAndGet();
            if (run == 3) throw new IllegalStateException("boom");
            return run < 3 ? Outcome.FIRST : Outcome.SECOND;
        };
        ProcessDefinition definition = builder -> builder.type("LOOP")
            .initialState(State.START)
            .step(State.START, step, routes -> routes.on(Outcome.FIRST, State.START).on(Outcome.SECOND, State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("LOOP")).start();
            var failed = emulator(engine).runUntilStop(id);
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertEquals(3, runs.get());
            assertEquals(1, failed.stateHistory().size());

            new ProcessEngineAdmin(engine).moveProcessToState(id, State.START);
            var moved = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.ACTIVE, moved.status());
            assertEquals(0, moved.attempt());
            assertEquals(1, moved.stateHistory().size());

            var completed = emulator(engine).runUntilStop(id);
            assertEquals(ProcessStatus.COMPLETED, completed.status());
            assertEquals(4, runs.get());
            assertEquals(List.of("START", "DONE"),
                    completed.stateHistory().stream().map(entry -> entry.state().name()).toList());
        } finally {
            engine.close();
        }
    }

    @Test
    void afterRunsHandlerThenEntersTarget() {
        var clock = new MutableTimeSource(Instant.parse("2026-09-24T10:00:00Z"));
        var reasons = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("AFTER")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.after(Duration.ofSeconds(5),
                    context -> reasons.add("timeout"), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withTimeSource(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("AFTER")).start();
            var started = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(State.WAIT.name(), started.state().name());
            assertEquals(Optional.of(Instant.parse("2026-09-24T10:00:05Z")), started.nextRunAt());
            assertEquals(State.WAIT.name(), emulator(engine, 20).runOneStep(id).state().name());
            assertTrue(reasons.isEmpty());
            clock.fastForwardTime(Duration.ofSeconds(5));
            assertEquals(ProcessStatus.COMPLETED, emulator(engine, 20).runOneStep(id).status());
            assertEquals(List.of("timeout"), reasons);
        } finally {
            engine.close();
        }
    }

    private enum Outcome {
        FIRST,
        SECOND
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        AFTER,
        CANCEL,
        DONE
    }

}
