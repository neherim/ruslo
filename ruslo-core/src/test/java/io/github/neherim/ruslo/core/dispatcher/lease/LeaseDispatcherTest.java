package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.TestDatabases;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.exception.WorkerNotRegisteredException;
import io.github.neherim.ruslo.core.metrics.InMemoryProcessEngineMetrics;
import io.github.neherim.ruslo.core.runtime.worker.Worker;
import io.github.neherim.ruslo.core.runtime.worker.WorkerRegistry;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.spi.RowMapper;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LeaseDispatcherTest extends BaseTest {
    private static final Instant TEST_NOW = Instant.parse("2026-07-19T10:15:30.123456Z");
    private static final Clock CLOCK = Clock.fixed(TEST_NOW, ZoneOffset.UTC);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(1);
    private static final ProcessType FIRST_TYPE = ProcessType.of("FIRST");
    private static final ProcessType SECOND_TYPE = ProcessType.of("SECOND");
    private static final WorkerId FIRST_WORKER = new WorkerId("first");
    private static final WorkerId SECOND_WORKER = new WorkerId("second");

    @Test
    void shouldNotClaimWithoutWorkerCapacity() {
        var worker = new TestWorker(FIRST_WORKER, 0);
        var fixture = dispatcher(worker);
        var processId = insertReady(FIRST_TYPE, 1);

        assertFalse(fixture.dispatcher().dispatch());

        assertNull(leaseOwner(processId));
        assertTrue(worker.tasks.isEmpty());
    }

    @Test
    void nextAvailableAtShouldBeEarliestAmongWorkersWithCapacity() {
        var fixture = dispatcher(new TestWorker(FIRST_WORKER, 0), new TestWorker(SECOND_WORKER, 1));
        insertReady(FIRST_TYPE, 1);
        insertReady(SECOND_TYPE, 3);
        insertReady(SECOND_TYPE, 2);

        assertEquals(Instant.parse("2026-07-19T10:00:00.002Z"), fixture.dispatcher().nextAvailableAt().orElseThrow());
    }

    @Test
    void zeroCapacityShouldNotAccessDatabase() {
        var worker = new TestWorker(FIRST_WORKER, 0);
        var forbiddenDatabase = new ForbiddenDatabase();
        var metrics = new ProcessEngineMetrics() {
        };
        var leaseDao = new LeaseDao(forbiddenDatabase, TestDatabases.dialect());
        var dispatcher = new LeaseDispatcher(UUID::randomUUID, leaseDao, new LeaseReleaser(leaseDao, metrics),
                new TestWorkerRegistry(List.of(worker)), metrics, CLOCK);

        assertFalse(dispatcher.dispatch());
        assertTrue(dispatcher.nextAvailableAt().isEmpty());

        assertFalse(forbiddenDatabase.accessed);
    }

    @Test
    void shouldClaimAllAvailableCapacityInOneDispatch() {
        var worker = new TestWorker(FIRST_WORKER, 5);
        var fixture = dispatcher(worker);
        for (int index = 0; index < 5; index++) {
            insertReady(FIRST_TYPE, index);
        }

        assertTrue(fixture.dispatcher().dispatch());
        assertEquals(5, worker.tasks.size());
        assertFalse(fixture.dispatcher().dispatch());
    }

    @Test
    void shouldScanPastWorkersWithoutCapacityAndBoundClaimsByCapacity() {
        var firstWorker = new TestWorker(FIRST_WORKER, 0);
        var secondWorker = new TestWorker(SECOND_WORKER, 2);
        var fixture = dispatcher(firstWorker, secondWorker);
        insertReady(FIRST_TYPE, 1);
        insertReady(FIRST_TYPE, 2);
        var selected = insertReady(SECOND_TYPE, 3);
        insertReady(SECOND_TYPE, 4);
        var notClaimed = insertReady(SECOND_TYPE, 5);

        assertTrue(fixture.dispatcher().dispatch());

        assertTrue(firstWorker.tasks.isEmpty());
        assertEquals(2, secondWorker.tasks.size());
        assertTrue(secondWorker.processIds().contains(selected));
        assertNull(leaseOwner(notClaimed));
        assertEquals(2, countClaimed());
    }

    @Test
    void shouldUseStoredWorkerAssignment() {
        var firstWorker = new TestWorker(FIRST_WORKER, 1);
        var secondWorker = new TestWorker(SECOND_WORKER, 1);
        var processId = insertReady(FIRST_TYPE, 1);
        var fixture = dispatcher(firstWorker, secondWorker);

        assertTrue(fixture.dispatcher().dispatch());

        assertEquals(Set.of(processId), firstWorker.processIds());
        assertTrue(secondWorker.tasks.isEmpty());
    }

    @Test
    void multipleDispatchersShouldNotSubmitOverlappingActiveLeases() throws Exception {
        var firstWorker = new TestWorker(FIRST_WORKER, 4);
        var secondWorker = new TestWorker(FIRST_WORKER, 4);
        var first = dispatcher(firstWorker);
        var second = dispatcher(secondWorker);
        var processIds = new HashSet<UUID>();
        for (int index = 0; index < 8; index++) {
            processIds.add(insertReady(FIRST_TYPE, index));
        }
        var start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstDispatch = executor.submit(() -> {
                start.await();
                return first.dispatcher().dispatch();
            });
            var secondDispatch = executor.submit(() -> {
                start.await();
                return second.dispatcher().dispatch();
            });
            start.countDown();
            assertTrue(firstDispatch.get(10, TimeUnit.SECONDS) || secondDispatch.get(10, TimeUnit.SECONDS));
        }

        var firstIds = firstWorker.processIds();
        var secondIds = secondWorker.processIds();
        assertTrue(java.util.Collections.disjoint(firstIds, secondIds));
        var allSubmitted = new HashSet<>(firstIds);
        allSubmitted.addAll(secondIds);
        assertFalse(allSubmitted.isEmpty());
        assertTrue(processIds.containsAll(allSubmitted));
        assertEquals(allSubmitted.size(), countClaimed());
    }

    @Test
    void shouldReleaseOnlyRejectedClaimAndAllowImmediateReclaim() {
        var rejecting = new TestWorker(FIRST_WORKER, 1);
        rejecting.accept = false;
        var first = dispatcher(rejecting);
        var processId = insertReady(FIRST_TYPE, 1);

        assertFalse(first.dispatcher().dispatch());
        assertNull(leaseOwner(processId));

        var accepting = new TestWorker(FIRST_WORKER, 1);
        var second = dispatcher(accepting);
        assertTrue(second.dispatcher().dispatch());
        assertEquals(Set.of(processId), accepting.processIds());
    }

    @Test
    void rejectedLeaseShouldBeReleasedWithoutPreventingRemainingClaims() {
        var worker = new TestWorker(FIRST_WORKER, 3);
        worker.accept = false;
        var metrics = new InMemoryProcessEngineMetrics();
        var fixture = dispatcher(metrics, worker);
        for (int index = 0; index < 3; index++) {
            insertReady(FIRST_TYPE, index);
        }

        assertFalse(fixture.dispatcher().dispatch());

        assertTrue(worker.tasks.isEmpty());
        assertEquals(0, countClaimed());
        assertEquals(0, metrics.getSystemErrorCount());
    }

    @Test
    void submissionExceptionShouldNotPreventRemainingClaims() {
        var worker = new TestWorker(FIRST_WORKER, 3);
        var attempts = new AtomicInteger();
        worker.beforeSubmission = ignored -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("synthetic submission failure");
            }
        };
        var metrics = new InMemoryProcessEngineMetrics();
        var fixture = dispatcher(metrics, worker);
        for (int index = 0; index < 3; index++) {
            insertReady(FIRST_TYPE, index);
        }

        assertTrue(fixture.dispatcher().dispatch());

        assertEquals(2, worker.tasks.size());
        assertEquals(2, countClaimed());
        assertEquals(0, metrics.getSystemErrorCount());
    }

    @Test
    void rejectedSubmissionShouldNotReleaseReplacementOwner() {
        var rejecting = new TestWorker(FIRST_WORKER, 1);
        rejecting.accept = false;
        var replacement = new AtomicReference<ProcessLease>();
        rejecting.beforeSubmission = lease -> {
            database.update("update xs_process_instance set lease_owner = null where id = ?",
                    lease.processId());
            var replacementOwner = UUID.randomUUID();
            new ApplicationInstanceDao(database).register(replacementOwner, LEASE_DURATION);
            replacement.set(new LeaseDao(database, TestDatabases.dialect()).claimReadyForWorker(
                    replacementOwner, FIRST_WORKER, 1, TEST_NOW)
                .getFirst());
        };
        var fixture = dispatcher(rejecting);
        var processId = insertReady(FIRST_TYPE, 1);

        assertFalse(fixture.dispatcher().dispatch());

        assertEquals(replacement.get().ownerId(), leaseOwner(processId));
        assertFalse(new LeaseDao(database, TestDatabases.dialect()).release(new ProcessLease(processId,
                fixture.ownerId())));
    }

    @Test
    void abandonedClaimShouldBeRecoveredAfterOwnerTimeout() {
        var processId = insertReady(FIRST_TYPE, 1);
        var abandonedOwner = UUID.randomUUID();
        var instances = new ApplicationInstanceDao(database);
        instances.register(abandonedOwner, LEASE_DURATION);
        var abandoned = new LeaseDao(database, TestDatabases.dialect()).claimReadyForWorker(
                abandonedOwner, FIRST_WORKER, 1, TEST_NOW)
            .getFirst();
        database.update("update xs_application_instance set expires_at = current_timestamp - interval '1' second "
                + "where id = ?", abandonedOwner);
        instances.recover();
        var worker = new TestWorker(FIRST_WORKER, 1);
        var fixture = dispatcher(worker);

        assertTrue(fixture.dispatcher().dispatch());

        assertEquals(Set.of(processId), worker.processIds());
        assertFalse(new LeaseDao(database, TestDatabases.dialect()).release(abandoned));
    }

    @Test
    void failedReleaseShouldBeRetriedBeforeNewClaims() {
        var failingDatabase = new FailingReleaseDatabase(database, 1);
        var worker = new TestWorker(FIRST_WORKER, 1);
        worker.accept = false;
        var metrics = new InMemoryProcessEngineMetrics();
        var fixture = dispatcher(failingDatabase, metrics, worker);
        var processId = insertReady(FIRST_TYPE, 1);

        assertFalse(fixture.dispatcher().dispatch());
        assertEquals(1, metrics.getSystemErrorCount());
        assertEquals(fixture.ownerId(), leaseOwner(processId));

        worker.accept = true;
        assertTrue(fixture.dispatcher().dispatch());
        assertEquals(Set.of(processId), worker.processIds());
        assertEquals(1, metrics.getSystemErrorCount());
    }

    @Test
    void unresolvedReleaseShouldNotBlockNewClaims() {
        var failingDatabase = new FailingReleaseDatabase(database, Integer.MAX_VALUE);
        var worker = new TestWorker(FIRST_WORKER, 1);
        worker.accept = false;
        var metrics = new InMemoryProcessEngineMetrics();
        var fixture = dispatcher(failingDatabase, metrics, worker);
        var firstProcess = insertReady(FIRST_TYPE, 1);

        assertFalse(fixture.dispatcher().dispatch());
        var secondProcess = insertReady(FIRST_TYPE, 2);
        worker.accept = true;
        assertTrue(fixture.dispatcher().dispatch());

        assertEquals(fixture.ownerId(), leaseOwner(firstProcess));
        assertEquals(Set.of(secondProcess), worker.processIds());
        assertEquals(2, metrics.getSystemErrorCount());
    }

    @Test
    void fencedPendingReleaseShouldBeDiscarded() {
        var failingDatabase = new FailingReleaseDatabase(database, 1);
        var worker = new TestWorker(FIRST_WORKER, 1);
        worker.accept = false;
        var metrics = new InMemoryProcessEngineMetrics();
        var fixture = dispatcher(failingDatabase, metrics, worker);
        var processId = insertReady(FIRST_TYPE, 1);

        assertFalse(fixture.dispatcher().dispatch());
        var replacementOwner = UUID.randomUUID();
        database.update("update xs_process_instance set lease_owner = null where id = ?", processId);
        new ApplicationInstanceDao(database).register(replacementOwner, LEASE_DURATION);
        var replacement = new LeaseDao(database, TestDatabases.dialect())
            .claimReadyForWorker(replacementOwner, FIRST_WORKER, 1, TEST_NOW)
            .getFirst();

        assertFalse(fixture.dispatcher().dispatch());

        assertEquals(replacementOwner, leaseOwner(processId));
        assertEquals(1, metrics.getSystemErrorCount());
        assertTrue(new LeaseDao(database, TestDatabases.dialect()).release(replacement));
    }

    @Test
    void workerClaimFailureShouldPreservePartialSuccess() {
        var firstWorker = new TestWorker(FIRST_WORKER, 1);
        var secondWorker = new TestWorker(SECOND_WORKER, 1);
        var fixture = dispatcher(new FailingWorkerClaimDatabase(database, SECOND_WORKER),
                new ProcessEngineMetrics() {
                }, firstWorker, secondWorker);
        var firstProcess = insertReady(FIRST_TYPE, 1);
        insertReady(SECOND_TYPE, 2);

        assertTrue(fixture.dispatcher().dispatch());

        assertEquals(Set.of(firstProcess), firstWorker.processIds());
        assertTrue(secondWorker.tasks.isEmpty());
    }

    private DispatcherFixture dispatcher(TestWorker... workers) {
        return dispatcher(new ProcessEngineMetrics() {
        }, workers);
    }

    private DispatcherFixture dispatcher(ProcessEngineMetrics metrics, TestWorker... workers) {
        return dispatcher(database, metrics, workers);
    }

    private DispatcherFixture dispatcher(
            Database dispatcherDatabase,
            ProcessEngineMetrics metrics,
            TestWorker... workers
    ) {
        var registry = new TestWorkerRegistry(List.of(workers));
        var ownerId = UUID.randomUUID();
        new ApplicationInstanceDao(dispatcherDatabase).register(ownerId, LEASE_DURATION);
        var leaseDao = new LeaseDao(dispatcherDatabase, TestDatabases.dialect());
        var dispatcher = new LeaseDispatcher(() -> ownerId, leaseDao, new LeaseReleaser(leaseDao, metrics),
                registry, metrics, CLOCK);
        return new DispatcherFixture(dispatcher, ownerId);
    }

    private UUID insertReady(ProcessType type, int order) {
        var id = new UUID(0, order + 1L);
        var readyAt = Instant.parse("2026-07-19T10:00:00Z").plusMillis(order);
        database.update("""
                insert into xs_process_instance
                    (id, create_at, state, worker_id, status, type, scheduled_at, available_at)
                values (?, current_timestamp, ?, ?, 'ACTIVE', ?, ?, ?)
                """, id, TestState.READY.name(), workerId(type).name(), type.name(),
                Timestamp.from(readyAt), Timestamp.from(readyAt));
        return id;
    }

    private UUID leaseOwner(UUID processId) {
        return database.queryForObject("select lease_owner from xs_process_instance where id = ?",
                rs -> rs.getObject(1, UUID.class), processId)
            .orElse(null);
    }

    private int countClaimed() {
        return database.queryForObject("select count(*) from xs_process_instance where lease_owner is not null",
                rs -> rs.getInt(1))
            .orElseThrow();
    }

    private WorkerId workerId(ProcessType type) {
        return type.equals(FIRST_TYPE) ? FIRST_WORKER : SECOND_WORKER;
    }

    private enum TestState implements ProcessState {
        READY
    }

    private record DispatcherFixture(LeaseDispatcher dispatcher, UUID ownerId) {
    }

    private static final class TestWorker implements Worker {
        private final WorkerId id;
        private final int capacity;
        private final List<ProcessLease> tasks = new ArrayList<>();
        private boolean accept = true;
        private Consumer<ProcessLease> beforeSubmission = ignored -> {};

        private TestWorker(WorkerId id, int capacity) {
            this.id = id;
            this.capacity = capacity;
        }

        @Override
        public WorkerSpec getSpec() {
            return WorkerSpec.builder(id).build();
        }

        @Override
        public synchronized int getAvailableCapacity() {
            return Math.max(0, capacity - tasks.size());
        }

        @Override
        public synchronized boolean tryExecute(ProcessLease lease) {
            beforeSubmission.accept(lease);
            if (accept && getAvailableCapacity() > 0) {
                tasks.add(lease);
                return true;
            }
            return false;
        }

        @Override
        public void close() {
        }

        private synchronized Set<UUID> processIds() {
            return tasks.stream().map(ProcessLease::processId).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static final class TestWorkerRegistry extends WorkerRegistry {
        private final Map<WorkerId, Worker> workers = new LinkedHashMap<>();

        private TestWorkerRegistry(Collection<? extends Worker> workers) {
            super(null);
            workers.forEach(worker -> this.workers.put(worker.getId(), worker));
        }

        @Override
        public Worker getWorker(WorkerId id) {
            var worker = workers.get(id);
            if (worker == null) {
                throw new WorkerNotRegisteredException(id);
            }
            return worker;
        }

        @Override
        public Collection<Worker> getAllWorkers() {
            return workers.values();
        }
    }

    private static class DelegatingDatabase implements Database {
        protected final Database delegate;

        private DelegatingDatabase(Database delegate) {
            this.delegate = delegate;
        }

        @Override
        public <T> T doInTransactionWithResult(Supplier<T> action) {
            return delegate.doInTransactionWithResult(action);
        }

        @Override
        public int update(String sql, Object... params) {
            return delegate.update(sql, params);
        }

        @Override
        public void batchInsert(String sql, List<Object[]> params) {
            delegate.batchInsert(sql, params);
        }

        @Override
        public <T> java.util.Optional<T> queryForObject(String sql, RowMapper<T> rowMapper, Object... params) {
            return delegate.queryForObject(sql, rowMapper, params);
        }

        @Override
        public List<Map<String, Object>> queryForMap(String sql, Object... params) {
            return delegate.queryForMap(sql, params);
        }

        @Override
        public <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params) {
            return delegate.queryForList(sql, rowMapper, params);
        }

        @Override
        public void doAfterCommit(Runnable action) {
            delegate.doAfterCommit(action);
        }

        @Override
        public DataSource getDataSource() {
            return delegate.getDataSource();
        }
    }

    private static final class FailingReleaseDatabase extends DelegatingDatabase {
        private final AtomicInteger failuresRemaining;

        private FailingReleaseDatabase(Database delegate, int failures) {
            super(delegate);
            this.failuresRemaining = new AtomicInteger(failures);
        }

        @Override
        public int update(String sql, Object... params) {
            if (sql.contains("where id = ? and lease_owner = ?")
                    && failuresRemaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                throw new IllegalStateException("synthetic release failure");
            }
            return super.update(sql, params);
        }
    }

    private static final class FailingWorkerClaimDatabase extends DelegatingDatabase {
        private final WorkerId failingWorker;

        private FailingWorkerClaimDatabase(Database delegate, WorkerId failingWorker) {
            super(delegate);
            this.failingWorker = failingWorker;
        }

        @Override
        public <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params) {
            if (List.of(params).contains(failingWorker.name())) {
                throw new IllegalStateException("synthetic claim failure");
            }
            return super.queryForList(sql, rowMapper, params);
        }
    }

    private static final class ForbiddenDatabase implements Database {
        private boolean accessed;

        @Override
        public <T> T doInTransactionWithResult(Supplier<T> action) {
            throw accessed();
        }

        @Override
        public int update(String sql, Object... params) {
            throw accessed();
        }

        @Override
        public void batchInsert(String sql, List<Object[]> params) {
            throw accessed();
        }

        @Override
        public <T> java.util.Optional<T> queryForObject(String sql, RowMapper<T> rowMapper, Object... params) {
            throw accessed();
        }

        @Override
        public List<Map<String, Object>> queryForMap(String sql, Object... params) {
            throw accessed();
        }

        @Override
        public <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params) {
            throw accessed();
        }

        @Override
        public void doAfterCommit(Runnable action) {
            throw accessed();
        }

        @Override
        public DataSource getDataSource() {
            throw accessed();
        }

        private IllegalStateException accessed() {
            accessed = true;
            return new IllegalStateException("Database access is forbidden");
        }
    }
}
