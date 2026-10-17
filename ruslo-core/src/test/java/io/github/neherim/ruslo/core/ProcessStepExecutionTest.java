package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
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
            engine.sendMessage(id, SIGNAL, "accepted");
            assertEquals(0, handled.get());
            assertTrue(engine.processes().byId(id).singleDetails().orElseThrow().scheduledAt().isEmpty());
            emulator(engine).runOneStep(id);
            assertEquals(1, handled.get());
            assertEquals(0, actionRan.get());
            assertEquals(State.AFTER.name(), engine.processes().byId(id).singleDetails().orElseThrow().state().name());
            emulator(engine).runOneStep(id);
            var completed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.COMPLETED, completed.status());
            assertEquals(Optional.of("accepted"), completed.variables().get(PAYLOAD));
        } finally {
            engine.close();
        }
    }

    @Test
    void runUntilStopPassesCommittedTransitionsUntilAwaitAndTerminal() {
        ProcessDefinition definition = builder -> builder.type("CHAIN")
            .initialState(State.START)
            .step(State.START, context -> {}, State.AFTER)
            .step(State.AFTER, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL,
                    (context, message) -> {}, State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("CHAIN")).start();
            assertEquals(State.WAIT.name(), emulator(engine).runUntilStop(id).state().name());
            var completed = emulator(engine).sendMessageAndRunUntilStop(id, SIGNAL, "go");
            assertEquals(ProcessStatus.COMPLETED, completed.status());
        } finally {
            engine.close();
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
            }, State.WAIT).timeout(Duration.ofDays(1), State.DONE))
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
            }, State.WAIT).timeout(Duration.ofDays(1), State.DONE))
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

    @Test
    void timerRunsOnlyWhenDue() {
        var clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        ProcessDefinition definition = builder -> builder.type("TIMER")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.timeout(Duration.ofSeconds(5), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withClock(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("TIMER")).start();
            emulator(engine, 20).runOneStep(id);
            assertEquals(ProcessStatus.ACTIVE, emulator(engine, 20).runOneStep(id).status());
            clock.advance(Duration.ofSeconds(5));
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
            assertTrue(failed.variables().get(TEMPORARY).isEmpty());
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
            }, State.WAIT).timeout(Duration.ofDays(1), State.DONE))
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
        var clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        var fail = new AtomicBoolean(true);
        var received = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("BACKOFF_MESSAGE")
            .onError((context, error) -> ErrorResult.retryAfter(Duration.ofSeconds(10)))
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                received.add(message.payload());
                if (fail.get()) throw new IllegalStateException("retry");
            }, State.WAIT).timeout(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withClock(clock)
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
            clock.advance(Duration.ofSeconds(10));
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
        var clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        var selected = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("EVENT_ORDER")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await
                .message(SIGNAL, (context, message) -> selected.add("message"), State.WAIT)
                .every(Duration.ofSeconds(5), context -> selected.add("timer"), State.WAIT)
                .timeout(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withClock(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("EVENT_ORDER")).start();
            emulator(engine, 20).runOneStep(id);
            clock.advance(messageDelay);
            engine.sendMessage(id, SIGNAL, "value");
            clock.advance(executeAfter.minus(messageDelay));
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
            }, State.WAIT).timeout(Duration.ofDays(1), State.DONE))
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
            }, State.WAIT).timeout(Duration.ofDays(1), State.DONE))
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
            emulator(engine).runOneStep(id);
            assertEquals(1, received.size());
            assertNull(received.getFirst());
        } finally {
            engine.close();
        }
    }

    @Test
    void timerSelfTargetUsesHandlingTimeAndFailureKeepsDueTimer() {
        var clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        var fail = new AtomicBoolean(true);
        var handled = new AtomicInteger();
        ProcessDefinition definition = builder -> builder.type("TIMER_RETRY")
            .onError((context, error) -> ErrorResult.fail())
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.every(Duration.ofSeconds(5), context -> {
                handled.incrementAndGet();
                if (fail.get()) throw new IllegalStateException("timer failed");
            }, State.WAIT).timeout(Duration.ofDays(1), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withClock(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("TIMER_RETRY")).start();
            emulator(engine, 20).runOneStep(id);
            var firstDue = engine.processes().byId(id).singleDetails().orElseThrow().scheduledAt().orElseThrow();
            clock.advance(Duration.ofSeconds(8));
            emulator(engine, 20).runOneStep(id);
            var failed = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.FAILED, failed.status());
            assertEquals(firstDue, failed.scheduledAt().orElseThrow());
            fail.set(false);
            engine.resumeFailedProcess(id);
            emulator(engine, 20).runOneStep(id);
            assertEquals(2, handled.get());
            assertEquals(clock.instant().plusSeconds(5),
                    engine.processes().byId(id).singleDetails().orElseThrow().scheduledAt().orElseThrow());
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
    void nullOutcomeFailsWithStateAndEventInLastError() {
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
            assertTrue(failed.lastError().orElseThrow().contains("Branching step in state START"),
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
            assertTrue(failed.lastError().orElseThrow().contains("message SIGNAL in state WAIT"),
                    failed.lastError().orElseThrow());
        } finally {
            other.close();
        }
    }

    @Test
    void awaitSelfTargetKeepsWaitingAndStepRestartsWaiting() {
        var clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        var handled = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("SELF")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await
                .message(SIGNAL, (context, message) -> {
                    handled.add(message.payload());
                    return message.payload().equals("restart") ? Outcome.FIRST : Outcome.SECOND;
                }, routes -> routes.on(Outcome.FIRST, State.AFTER).on(Outcome.SECOND, State.WAIT))
                .timeout(Duration.ofSeconds(50), State.DONE))
            .step(State.AFTER, context -> {}, State.WAIT)
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withClock(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("SELF")).start();
            clock.advance(Duration.ofSeconds(10));
            engine.sendMessage(id, SIGNAL, "keep");
            engine.sendMessage(id, SIGNAL, "restart");
            engine.sendMessage(id, SIGNAL, "obsolete");
            var kept = emulator(engine, 20).runOneStep(id);
            assertEquals(1, kept.stateHistory().stream().filter(entry -> entry.state().is(State.WAIT)).count());
            emulator(engine, 20).runOneStep(id);
            emulator(engine, 20).runOneStep(id);
            var reentered = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(2, reentered.stateHistory().stream().filter(entry -> entry.state().is(State.WAIT)).count());
            assertEquals(List.of("keep", "restart"), handled);
            clock.advance(Duration.ofSeconds(49));
            assertEquals(State.WAIT.name(), emulator(engine, 20).runOneStep(id).state().name());
            clock.advance(Duration.ofSeconds(1));
            assertEquals(ProcessStatus.COMPLETED, emulator(engine, 20).runOneStep(id).status());
        } finally {
            engine.close();
        }
    }

    @Test
    void timeoutRunsHandlerThenEntersTarget() {
        var clock = new MutableClock(Instant.parse("2026-09-24T10:00:00Z"));
        var reasons = new ArrayList<String>();
        ProcessDefinition definition = builder -> builder.type("TIMEOUT")
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.timeout(Duration.ofSeconds(5),
                    context -> reasons.add("timeout"), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = new ProcessEngineBuilder(database).withClock(clock)
            .addProcessDefinition(definition)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(false)
            .build()
            .start();
        try {
            var id = engine.newProcess(ProcessType.of("TIMEOUT")).start();
            var started = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(State.WAIT.name(), started.state().name());
            assertTrue(started.scheduledAt().isEmpty());
            assertEquals(State.WAIT.name(), emulator(engine, 20).runOneStep(id).state().name());
            assertTrue(reasons.isEmpty());
            clock.advance(Duration.ofSeconds(5));
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
        DONE
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

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
}
