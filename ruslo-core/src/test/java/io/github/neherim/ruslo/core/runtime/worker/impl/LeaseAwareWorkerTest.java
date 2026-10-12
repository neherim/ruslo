package io.github.neherim.ruslo.core.runtime.worker.impl;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceDao;
import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;
import io.github.neherim.ruslo.core.runtime.worker.WorkerTestFixture;
import io.github.neherim.ruslo.core.utils.thread.CurrentThreadExecutorService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.github.neherim.ruslo.core.TestAwait.awaitLatch;
import static io.github.neherim.ruslo.core.TestAwait.join;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class LeaseAwareWorkerTest extends BaseTest {
    @Test
    void transactionalFailureRollsBackUserDatabaseChangesAndCanBeRetried() {
        database.update("CREATE TABLE tx_lease_effect (effect_value INTEGER NOT NULL)");
        var shouldFail = new AtomicBoolean(true);
        var executions = new AtomicInteger();
        var type = ProcessType.of("LEASE_TRANSACTIONAL_ROLLBACK");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(TestState.INIT)
            .step(TestState.INIT, context -> {
                executions.incrementAndGet();
                database.update("INSERT INTO tx_lease_effect (effect_value) VALUES (1)");
                if (shouldFail.getAndSet(false)) {
                    throw new IllegalStateException("Expected transactional failure");
                }
            }, TestState.DONE)
            .terminal(TestState.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(type, "tx-rollback");

        fixture.transactionalWorker().tryExecute(fixture.task(process));

        assertEquals(0L, effectCount());
        var failed = fixture.dao().findById(process.getId()).orElseThrow();
        failed.resume();
        fixture.dao().update(failed);
        fixture.transactionalWorker().tryExecute(fixture.task(process));

        assertEquals(2, executions.get());
        assertEquals(1L, effectCount());
        assertTrue(fixture.dao().findById(process.getId()).orElseThrow().isCompleted());
    }

    @Test
    void transactionalWorkerDoesNotExecuteUserCodeWithStaleOwner() {
        var executions = new AtomicInteger();
        var type = ProcessType.of("LEASE_TX_STALE");
        var fixture = WorkerTestFixture.with(database, completingDefinition(type, executions));
        var process = fixture.insertNewProcess(type, "tx-stale");
        var staleLease = claim(fixture, process.getId());
        var currentLease = replaceLease(fixture, process.getId());

        fixture.transactionalLeaseWorker(new CurrentThreadExecutorService(), () -> {})
            .tryExecute(fixture.leaseTask(staleLease));

        assertEquals(0, executions.get());
        assertEquals(currentLease.ownerId(), storedOwner(process.getId()));
        assertFalse(fixture.dao().findById(process.getId()).orElseThrow().isCompleted());
    }

    @Test
    void transactionalRowLockPreventsReclaimAfterNominalExpiry() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var type = ProcessType.of("LEASE_TX_LONG");
        var fixture = WorkerTestFixture.with(database, blockingDefinition(type, entered, release, executions, false));
        var process = fixture.insertNewProcess(type, "tx-long");
        var task = fixture.leaseTask(process);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            fixture.transactionalLeaseWorker(executor, () -> {}).tryExecute(task);
            awaitLatch(entered, "Transactional lease worker should enter user code");
            var replacementOwner = registerOwner();
            assertTrue(fixture.leaseDao()
                .claimReadyForWorker(replacementOwner, process.getWorkerId().orElseThrow(), 1, Instant.now())
                .isEmpty());
            release.countDown();
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertTrue(
                        fixture.dao().findById(process.getId()).orElseThrow().isCompleted()));
        } finally {
            release.countDown();
        }

        assertEquals(1, executions.get());
        assertNull(storedOwner(process.getId()));
        assertEquals(1L, storedVersion(process.getId()));
    }

    @Test
    void nonTransactionalVersionConflictPreservesConcurrentUpdate() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var type = ProcessType.of("LEASE_NON_TX_VERSION");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(TestState.INIT)
            .step(TestState.INIT, context -> {
                entered.countDown();
                awaitLatch(release, "Test should release non-transactional step");
                context.setVariable("stale", true);
            }, TestState.DONE)
            .terminal(TestState.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(type, "version-conflict");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = fixture.nonTransactionalLeaseWorker(1, executor, () -> {});
            worker.tryExecute(fixture.leaseTask(process));
            awaitLatch(entered, "Non-transactional step should start");
            var concurrent = fixture.dao().findById(process.getId()).orElseThrow();
            concurrent.setVariable("fresh", true);
            fixture.dao().update(concurrent);
            release.countDown();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertNull(storedOwner(process.getId())));
        } finally {
            release.countDown();
        }

        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertTrue(stored.getVariable("fresh", Boolean.class));
        assertNull(stored.getVariable("stale", Boolean.class));
        assertFalse(stored.isCompleted());
    }

    @Test
    void messageCannotBeAppendedWhileNonTransactionalActionRuns() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var type = ProcessType.of("LEASE_NON_TX_MESSAGE");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(TestState.INIT)
            .step(TestState.INIT, context -> {
                entered.countDown();
                awaitLatch(release, "Test should release non-transactional message step");
                context.setVariable("stale", true);
            }, TestState.DONE)
            .terminal(TestState.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(type, "message-conflict");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = fixture.nonTransactionalLeaseWorker(1, executor, () -> {});
            worker.tryExecute(fixture.leaseTask(process));
            awaitLatch(entered, "Non-transactional message step should start");
            var concurrent = fixture.dao().findById(process.getId()).orElseThrow();
            assertTrue(concurrent.expectedMessageEvent(
                    io.github.neherim.ruslo.core.api.definition.MessageType.of("signal", String.class))
                .isEmpty());
            release.countDown();

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertNull(storedOwner(process.getId())));
        } finally {
            release.countDown();
        }

        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertTrue(stored.getMessages().isEmpty());
        assertTrue(stored.getVariable("stale", Boolean.class));
        assertTrue(stored.isCompleted());
    }

    @Test
    void replacedOwnerFencesNonTransactionalPersistence() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var type = ProcessType.of("LEASE_NON_TX_RENEWAL_LOSS");
        var fixture = WorkerTestFixture.with(database,
                blockingDefinition(type, entered, release, executions, false));
        var process = fixture.insertNewProcess(type, "renewal-loss");
        var staleTask = fixture.leaseTask(process);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = fixture.nonTransactionalLeaseWorker(1, executor, () -> {});
            worker.tryExecute(staleTask);
            awaitLatch(entered, "Non-transactional step should start before lease replacement");
            var currentLease = replaceLease(fixture, process.getId());

            assertEquals(currentLease.ownerId(), storedOwner(process.getId()));
            release.countDown();
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(currentLease.ownerId(), storedOwner(process.getId())));
            assertEquals(currentLease.ownerId(), storedOwner(process.getId()));
        } finally {
            release.countDown();
        }

        assertEquals(1, executions.get());
        assertFalse(fixture.dao().findById(process.getId()).orElseThrow().isCompleted());
    }

    @Test
    void transitionToAnotherWorkerReleasesLease() {
        var nextWorker = new WorkerId("next-worker");
        var type = ProcessType.of("LEASE_TRANSITION_OTHER_WORKER");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(TransitionState.INIT)
            .step(TransitionState.INIT, context -> {}, TransitionState.NEXT)
            .step(TransitionState.NEXT, context -> {}, TransitionState.DONE,
                    options -> options.worker(nextWorker))
            .terminal(TransitionState.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(type, "transition-other-worker");

        fixture.transactionalWorker().tryExecute(fixture.task(process));

        assertNull(storedOwner(process.getId()));
        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals(nextWorker, stored.getWorkerId().orElseThrow());
        assertEquals(TransitionState.NEXT.name(), stored.getState().name());
        assertEquals(process.getId(), fixture.claim(stored).processId());
    }

    @Test
    void transactionalWorkerContinuesChainUnderSameLease() {
        verifyChainUnderSameLease(true);
    }

    @Test
    void nonTransactionalWorkerContinuesChainUnderSameLease() {
        verifyChainUnderSameLease(false);
    }

    @Test
    void nonTransactionalVersionConflictDoesNotRunNextStep() {
        var nextExecutions = new AtomicInteger();
        var fixtureRef = new AtomicReference<WorkerTestFixture>();
        var type = ProcessType.of("LEASE_NON_TX_TRANSITION_CONFLICT");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(TransitionState.INIT)
            .step(TransitionState.INIT, context -> {
                context.setVariable("stale", true);
                var concurrent = fixtureRef.get().dao().findById(context.processId()).orElseThrow();
                concurrent.setVariable("fresh", true);
                fixtureRef.get().dao().update(concurrent);
            }, TransitionState.NEXT)
            .step(TransitionState.NEXT, context -> {
                nextExecutions.incrementAndGet();
            }, TransitionState.DONE)
            .terminal(TransitionState.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        fixtureRef.set(fixture);
        var process = fixture.insertNewProcess(type, "transition-conflict");

        fixture.nonTransactionalLeaseWorker(1, new CurrentThreadExecutorService(), () -> {})
            .tryExecute(fixture.task(process));

        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertTrue(stored.getVariable("fresh", Boolean.class));
        assertNull(stored.getVariable("stale", Boolean.class));
        assertEquals(TransitionState.INIT.name(), stored.getState().name());
        assertEquals(0, nextExecutions.get());
        assertNull(storedOwner(process.getId()));
    }

    @Test
    void transactionalFailureInSecondStepKeepsFirstStepCommitted() {
        verifyFailureInSecondStep(true);
    }

    @Test
    void nonTransactionalFailureInSecondStepKeepsFirstStepCommitted() {
        verifyFailureInSecondStep(false);
    }

    @Test
    void stepErrorHandlerDoesNotMutateProcessAfterLeaseLoss() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var type = ProcessType.of("LEASE_ERROR_STALE");
        var fixture = WorkerTestFixture.with(database,
                blockingDefinition(type, entered, release, new AtomicInteger(), true));
        var process = fixture.insertNewProcess(type, "stale-error");
        var staleTask = fixture.leaseTask(process);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = fixture.nonTransactionalLeaseWorker(1, executor, () -> {});
            worker.tryExecute(staleTask);
            awaitLatch(entered, "Failing step should start");
            var currentLease = replaceLease(fixture, process.getId());
            release.countDown();
            await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertEquals(currentLease.ownerId(), storedOwner(process.getId())));
        } finally {
            release.countDown();
        }

        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals(0, stored.getAttempt());
        assertTrue(stored.getLastError().isEmpty());
    }

    @Test
    void workerAcceptsEveryLeaseWithoutProcessDeduplication() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var type = ProcessType.of("LEASE_NO_LOCAL_DEDUP");
        var fixture = WorkerTestFixture.with(database,
                blockingDefinition(type, entered, release, executions, false));
        var process = fixture.insertNewProcess(type, "no-local-dedup");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = fixture.nonTransactionalLeaseWorker(2, executor, () -> {});
            assertTrue(worker.tryExecute(fixture.leaseTask(process)));
            awaitLatch(entered, "First lease task should enter user code");
            var replacement = replaceLease(fixture, process.getId());

            assertTrue(worker.tryExecute(fixture.leaseTask(replacement)));
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertEquals(2, executions.get()));
            assertEquals(replacement.ownerId(), storedOwner(process.getId()));
            release.countDown();
            worker.close();
        } finally {
            release.countDown();
        }

        assertEquals(2, executions.get());
        assertTrue(fixture.dao().findById(process.getId()).orElseThrow().isCompleted());
    }

    @Test
    void workerRejectsWhenConcurrencyIsFullWithoutLocalQueue() {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var type = ProcessType.of("LEASE_NO_LOCAL_QUEUE");
        var fixture = WorkerTestFixture.with(database,
                blockingDefinition(type, entered, release, new AtomicInteger(), false));
        var running = fixture.insertNewProcess(type, "running");
        var rejected = fixture.insertNewProcess(type, "rejected");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var worker = fixture.nonTransactionalLeaseWorker(1, false, executor, () -> {});
            assertTrue(worker.tryExecute(fixture.leaseTask(running)));
            awaitLatch(entered, "First task should occupy worker capacity");
            var rejectedLease = fixture.leaseTask(rejected);
            assertFalse(worker.tryExecute(rejectedLease));
            assertEquals(rejectedLease.ownerId(), storedOwner(rejected.getId()));
            assertTrue(fixture.leaseDao().release(rejectedLease));

            var closer = Thread.ofVirtual().start(worker::close);
            release.countDown();
            join(closer, "Worker close should wait for the running task");
        } finally {
            release.countDown();
        }

        assertNull(storedOwner(running.getId()));
    }

    @Test
    void completionHookRunsAfterLeaseTaskFinishes() {
        var completions = new AtomicInteger();
        var type = ProcessType.of("LEASE_COMPLETION_HOOK");
        var fixture = WorkerTestFixture.with(database, completingDefinition(type, new AtomicInteger()));
        var process = fixture.insertNewProcess(type, "completion-hook");

        fixture.transactionalLeaseWorker(new CurrentThreadExecutorService(), completions::incrementAndGet)
            .tryExecute(fixture.leaseTask(process));

        assertEquals(1, completions.get());
    }

    private ProcessLease claim(WorkerTestFixture fixture, UUID processId) {
        var process = fixture.dao().findById(processId).orElseThrow();
        return fixture.claim(process);
    }

    private void verifyChainUnderSameLease(boolean transactional) {
        var executions = new AtomicInteger();
        var type = ProcessType.of("LEASE_CHAIN_" + transactional);
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(CheckpointState.FIRST)
            .step(CheckpointState.FIRST, context -> {
                executions.incrementAndGet();
                context.setVariable("first", "saved");
            }, CheckpointState.SECOND)
            .step(CheckpointState.SECOND, context -> {
                executions.incrementAndGet();
                assertTrue(database.queryForObject(
                        "select variables from xs_process_instance where id = ?",
                        rs -> rs.getString(1), context.processId())
                    .orElseThrow()
                    .contains("saved"));
                assertNotNull(storedOwner(context.processId()));
            }, CheckpointState.THIRD)
            .step(CheckpointState.THIRD, context -> {
                executions.incrementAndGet();
                assertNotNull(storedOwner(context.processId()));
            }, CheckpointState.DONE)
            .terminal(CheckpointState.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(type, "chain");
        var task = fixture.task(process);

        if (transactional) {
            fixture.transactionalWorker().tryExecute(task);
        } else {
            fixture.nonTransactionalLeaseWorker(1, new CurrentThreadExecutorService(), () -> {}).tryExecute(task);
        }

        assertEquals(3, executions.get());
        assertTrue(fixture.dao().findById(process.getId()).orElseThrow().isCompleted());
        assertNull(storedOwner(process.getId()));
        assertEquals(3L, storedVersion(process.getId()));
        assertEquals(4L, database.queryForObject(
                "select count(*) from xs_process_state_hist where link_process_instance = ?",
                rs -> rs.getLong(1), process.getId())
            .orElseThrow());
    }

    private void verifyFailureInSecondStep(boolean transactional) {
        var firstExecutions = new AtomicInteger();
        var shouldFail = new AtomicBoolean(true);
        var type = ProcessType.of("LEASE_SECOND_STEP_FAILURE_" + transactional);
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(CheckpointState.FIRST)
            .step(CheckpointState.FIRST, context -> {
                firstExecutions.incrementAndGet();
                context.setVariable("first", "saved");
            }, CheckpointState.SECOND)
            .step(CheckpointState.SECOND, context -> {
                if (shouldFail.getAndSet(false)) {
                    throw new IllegalStateException("Expected failure in second step");
                }
            }, CheckpointState.DONE)
            .terminal(CheckpointState.DONE)
            .build();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(type, "second-step-failure");
        var worker = transactional
                ? fixture.transactionalWorker()
                : fixture.nonTransactionalLeaseWorker(1, new CurrentThreadExecutorService(), () -> {});

        worker.tryExecute(fixture.task(process));

        var failed = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals(CheckpointState.SECOND.name(), failed.getState().name());
        assertEquals("saved", failed.getVariable("first", String.class));
        assertFalse(failed.isCompleted());
        failed.resume();
        fixture.dao().update(failed);
        worker.tryExecute(fixture.task(process));

        assertEquals(1, firstExecutions.get());
        assertTrue(fixture.dao().findById(process.getId()).orElseThrow().isCompleted());
    }

    private void expire(UUID processId) {
        database.update("update xs_process_instance set lease_owner = null where id = ?", processId);
    }

    private UUID storedOwner(UUID processId) {
        return database.queryForObject("select lease_owner from xs_process_instance where id = ?",
                rs -> rs.getObject(1, UUID.class), processId)
            .orElse(null);
    }

    private long storedVersion(UUID processId) {
        return database.queryForObject("select row_version from xs_process_instance where id = ?",
                rs -> rs.getLong(1), processId)
            .orElseThrow();
    }

    private long effectCount() {
        return database.queryForObject("SELECT COUNT(*) FROM tx_lease_effect", rs -> rs.getLong(1))
            .orElseThrow();
    }

    private static ProcessDefinition completingDefinition(ProcessType type, AtomicInteger executions) {
        return builder -> builder.type(type)
            .initialState(TestState.INIT)
            .step(TestState.INIT, context -> {
                executions.incrementAndGet();
            }, TestState.DONE)
            .terminal(TestState.DONE)
            .build();
    }

    private ProcessLease replaceLease(WorkerTestFixture fixture, UUID processId) {
        expire(processId);
        var replacementOwner = registerOwner();
        var process = fixture.dao().findById(processId).orElseThrow();
        return fixture.leaseDao()
            .claimReadyForWorker(replacementOwner, process.getWorkerId().orElseThrow(), 1, Instant.now())
            .stream()
            .findFirst()
            .orElseThrow();
    }

    private UUID registerOwner() {
        var owner = UUID.randomUUID();
        new ApplicationInstanceDao(database).register(owner, Duration.ofMinutes(1));
        return owner;
    }

    private static ProcessDefinition blockingDefinition(
            ProcessType type,
            CountDownLatch entered,
            CountDownLatch release,
            AtomicInteger executions,
            boolean fail
    ) {
        return builder -> builder.type(type)
            .initialState(TestState.INIT)
            .step(TestState.INIT, context -> {
                executions.incrementAndGet();
                entered.countDown();
                awaitLatch(release, "Test should release process step");
                if (fail) {
                    throw new IllegalStateException("Expected lease-aware test failure");
                }
            }, TestState.DONE)
            .terminal(TestState.DONE)
            .build();
    }

    private enum TestState implements ProcessState {
        INIT,
        DONE
    }

    private enum TransitionState implements ProcessState {
        INIT,
        NEXT,
        DONE
    }

    private enum CheckpointState implements ProcessState {
        FIRST,
        SECOND,
        THIRD,
        DONE
    }
}
