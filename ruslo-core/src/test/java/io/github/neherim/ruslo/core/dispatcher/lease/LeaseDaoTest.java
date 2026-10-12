package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.TestDatabases;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.management.DatabaseSchemaMigrator;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import io.github.neherim.ruslo.core.spi.RowMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class LeaseDaoTest {
    private static final Duration INSTANCE_TIMEOUT = Duration.ofMinutes(1);
    private static final WorkerId TEST_WORKER = new WorkerId("lease-worker");
    private static final Instant NOW = Instant.parse("2026-07-20T10:15:30.123456Z");

    private DataSource dataSource;
    private Database database;
    private CountingDatabase countingDatabase;
    private LeaseDao leaseDao;
    private UUID ownerId;

    @BeforeEach
    void setUp() {
        dataSource = TestDatabases.newDataSource();
        countingDatabase = new CountingDatabase(dataSource);
        database = countingDatabase;
        DatabaseSchemaMigrator.update(dataSource);
        leaseDao = new LeaseDao(database, TestDatabases.dialect());
        ownerId = registerOwner();
    }

    @Test
    void shouldClaimInStartDateOrderAndUseEngineTime() {
        var before = NOW;
        insertReady(uuid(3), before.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        insertReady(uuid(2), before.minusSeconds(2), ProcessStatus.ACTIVE, TEST_WORKER);
        insertReady(uuid(1), before.minusSeconds(2), ProcessStatus.ACTIVE, TEST_WORKER);

        var claims = leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 3, NOW);

        assertEquals(List.of(uuid(1), uuid(2), uuid(3)), claims.stream()
            .map(ProcessLease::processId)
            .toList());
        assertTrue(claims.stream().allMatch(lease -> ownerId.equals(lease.ownerId())));
        assertEquals(0L, rowVersion(uuid(1)));
        assertEquals(0L, rowVersion(uuid(2)));
        assertEquals(0L, rowVersion(uuid(3)));
    }

    @Test
    void shouldFilterWaitingFutureCompletedOwnedAndOtherWorkerProcesses() {
        var now = NOW;
        insertWaiting(uuid(1));
        insertReady(uuid(2), now.plusSeconds(60), ProcessStatus.ACTIVE, TEST_WORKER);
        insertReady(uuid(3), now.minusSeconds(1), ProcessStatus.COMPLETED, TEST_WORKER);
        insertReady(uuid(4), now.minusSeconds(1), ProcessStatus.ACTIVE, new WorkerId("other"));
        insertReady(uuid(5), now.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        insertReady(uuid(6), now.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        assertEquals(1, leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 1, NOW).size());

        var claims = leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 10, NOW);

        assertEquals(List.of(uuid(6)), claims.stream().map(ProcessLease::processId).toList());
    }

    @Test
    void shouldFindNextAvailableAtAmongUnclaimedActiveProcessesOfWorker() {
        insertWaiting(uuid(1));
        insertReady(uuid(2), NOW.plusSeconds(30), ProcessStatus.ACTIVE, TEST_WORKER);
        insertReady(uuid(3), NOW.plusSeconds(10), ProcessStatus.ACTIVE, TEST_WORKER);
        insertReady(uuid(4), NOW.plusSeconds(1), ProcessStatus.COMPLETED, TEST_WORKER);
        insertReady(uuid(5), NOW.plusSeconds(2), ProcessStatus.ACTIVE, new WorkerId("other"));
        insertReady(uuid(6), NOW.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 1, NOW);

        assertEquals(Optional.of(NOW.plusSeconds(10)), leaseDao.findNextAvailableAt(TEST_WORKER));
        assertEquals(Optional.empty(), leaseDao.findNextAvailableAt(new WorkerId("missing")));
    }

    @Test
    void shouldClaimWithOneDatabaseStatement() {
        insertReady(uuid(1), NOW.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        countingDatabase.resetCounters();

        var claims = leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 1, NOW);

        assertEquals(1, claims.size());
        assertEquals(1, countingDatabase.queryForListCalls);
        assertEquals(0, countingDatabase.queryForObjectCalls);
        assertEquals(0, countingDatabase.updateCalls);
    }

    @Test
    void shouldNotQueryDatabaseForNonPositiveLimit() {
        countingDatabase.resetCounters();

        assertTrue(leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 0, NOW).isEmpty());

        assertEquals(0, countingDatabase.queryForListCalls);
        assertEquals(0, countingDatabase.queryForObjectCalls);
        assertEquals(0, countingDatabase.updateCalls);
    }

    @Test
    void shouldRejectClaimsFromMissingOrStaleOwner() {
        insertReady(uuid(1), NOW.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        assertTrue(leaseDao.claimReadyForWorker(UUID.randomUUID(), TEST_WORKER, 1, NOW).isEmpty());

        database.update("update xs_application_instance set expires_at = current_timestamp - interval '1' second "
                + "where id = ?", ownerId);

        assertTrue(leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 1, NOW).isEmpty());
    }

    @Test
    void shouldAllowOnlyOneConcurrentClaim() throws Exception {
        insertReady(uuid(1), NOW.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        var secondOwner = registerOwner();
        var firstDao = new LeaseDao(new PlainJdbcDatabase(dataSource), TestDatabases.dialect());
        var secondDao = new LeaseDao(new PlainJdbcDatabase(dataSource), TestDatabases.dialect());
        var start = new java.util.concurrent.CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return firstDao.claimReadyForWorker(ownerId, TEST_WORKER, 1, NOW).stream().findFirst().orElse(null);
            });
            var second = executor.submit(() -> {
                start.await();
                return secondDao.claimReadyForWorker(secondOwner, TEST_WORKER, 1, NOW)
                    .stream()
                    .findFirst()
                    .orElse(null);
            });
            start.countDown();
            assertEquals(1,
                    java.util.Arrays.asList(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                        .stream()
                        .filter(java.util.Objects::nonNull)
                        .count());
        }
    }

    @Test
    void releaseShouldBeImmediateAndFencedByOwner() {
        insertReady(uuid(1), NOW.minusSeconds(1), ProcessStatus.ACTIVE, TEST_WORKER);
        var lease = leaseDao.claimReadyForWorker(ownerId, TEST_WORKER, 1, NOW).getFirst();
        var anotherOwnerId = registerOwner();
        var foreignLease = new ProcessLease(lease.processId(), anotherOwnerId);

        assertFalse(leaseDao.release(foreignLease));
        assertTrue(leaseDao.release(lease));
        assertFalse(leaseDao.release(lease));
        assertEquals(0L, rowVersion(uuid(1)));
        var replacement = leaseDao.claimReadyForWorker(anotherOwnerId, TEST_WORKER, 1, NOW);
        assertEquals(List.of(uuid(1)), replacement.stream().map(ProcessLease::processId).toList());
    }

    private UUID registerOwner() {
        var id = UUID.randomUUID();
        new ApplicationInstanceDao(database).register(id, INSTANCE_TIMEOUT);
        return id;
    }

    private void insertReady(UUID id, Instant scheduledAt, ProcessStatus status, WorkerId workerId) {
        database.update("""
                insert into xs_process_instance
                    (id, create_at, state, worker_id, status, type, scheduled_at, available_at)
                values (?, current_timestamp, 'STARTED', ?, ?, 'lease_test', ?, ?)
                """, id, workerId.name(), status.name(),
                Timestamp.from(scheduledAt), Timestamp.from(scheduledAt));
    }

    private void insertWaiting(UUID id) {
        database.update("""
                insert into xs_process_instance
                    (id, create_at, state, worker_id, status, type, scheduled_at, available_at)
                values (?, current_timestamp, 'STARTED', 'lease-worker', 'ACTIVE', 'lease_test', null, null)
                """, id);
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private long rowVersion(UUID processId) {
        return database.queryForObject("select row_version from xs_process_instance where id = ?",
                rs -> rs.getLong(1), processId)
            .orElseThrow();
    }

    private static final class CountingDatabase extends PlainJdbcDatabase {
        private int updateCalls;
        private int queryForObjectCalls;
        private int queryForListCalls;

        private CountingDatabase(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public int update(String sql, Object... params) {
            updateCalls++;
            return super.update(sql, params);
        }

        @Override
        public <T> Optional<T> queryForObject(String sql, RowMapper<T> rowMapper, Object... params) {
            queryForObjectCalls++;
            return super.queryForObject(sql, rowMapper, params);
        }

        @Override
        public <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params) {
            queryForListCalls++;
            return super.queryForList(sql, rowMapper, params);
        }

        private void resetCounters() {
            updateCalls = 0;
            queryForObjectCalls = 0;
            queryForListCalls = 0;
        }
    }
}
