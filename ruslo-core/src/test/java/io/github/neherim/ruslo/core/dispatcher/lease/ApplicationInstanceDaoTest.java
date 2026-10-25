package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.TestDatabases;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationInstanceDaoTest extends BaseTest {
    private static final Duration INSTANCE_TIMEOUT = Duration.ofMinutes(1);
    private static final WorkerId WORKER = new WorkerId("heartbeat-worker");
    private static final Instant NOW = Instant.parse("2099-07-20T10:15:30.123456Z");

    @Test
    void registerShouldCountExpiryFromDatabaseTime() {
        var instances = new ApplicationInstanceDao(database);
        var instanceId = UUID.randomUUID();

        instances.register(instanceId, Duration.ofMinutes(10));

        var matching = database.queryForObject("""
                select count(*) from xs_application_instance
                where id = ?
                  and expires_at > current_timestamp + interval '9' minute
                  and expires_at <= current_timestamp + interval '10' minute
                """, rs -> rs.getInt(1), instanceId).orElseThrow();
        assertEquals(1, matching);
    }

    @Test
    void recoveryShouldRemoveOnlyExpiredOwnerAndFenceItsOldLease() {
        var instances = new ApplicationInstanceDao(database);
        var leases = new LeaseDao(database, TestDatabases.dialect());
        var activeOwner = UUID.randomUUID();
        var expiredOwner = UUID.randomUUID();
        instances.register(activeOwner, INSTANCE_TIMEOUT);
        instances.register(expiredOwner, INSTANCE_TIMEOUT);
        insertReady(new UUID(0, 1));
        insertReady(new UUID(0, 2));
        var activeLease = leases.claimReadyForWorker(activeOwner, WORKER, 1, NOW).getFirst();
        var expiredLease = leases.claimReadyForWorker(expiredOwner, WORKER, 1, NOW).getFirst();
        expire(expiredOwner);

        var result = instances.recover();

        assertEquals(new ApplicationInstanceDao.RecoveryResult(1, 1), result);
        assertEquals(activeOwner, owner(activeLease.processId()));
        assertNull(owner(expiredLease.processId()));
        assertTrue(leases.claimReadyForWorker(expiredOwner, WORKER, 1, NOW).isEmpty());
        var replacementOwner = UUID.randomUUID();
        instances.register(replacementOwner, INSTANCE_TIMEOUT);
        leases.claimReadyForWorker(replacementOwner, WORKER, 1, NOW).getFirst();
        assertFalse(leases.release(expiredLease));
        assertEquals(replacementOwner, owner(expiredLease.processId()));
        assertEquals(0L, processVersion(expiredLease.processId()));
    }

    @Test
    void recoveryShouldReturnZeroWhenThereIsNothingToRecover() {
        var instances = new ApplicationInstanceDao(database);

        var result = instances.recover();

        assertEquals(new ApplicationInstanceDao.RecoveryResult(0, 0), result);
    }

    @Test
    void recoveryShouldReleaseLeaseOfUnknownOwner() {
        var instances = new ApplicationInstanceDao(database);
        var processId = new UUID(0, 1);
        insertReady(processId);
        database.update("update xs_process_instance set lease_owner = ? where id = ?", UUID.randomUUID(), processId);

        var result = instances.recover();

        assertEquals(new ApplicationInstanceDao.RecoveryResult(1, 0), result);
        assertNull(owner(processId));
    }

    @Test
    void recoveryShouldSkipLockedLeaseWithoutWaiting() throws Exception {
        var instances = new ApplicationInstanceDao(database);
        var processId = new UUID(0, 1);
        insertReady(processId);
        database.update("update xs_process_instance set lease_owner = ? where id = ?", UUID.randomUUID(), processId);

        try (var connection = database.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("select id from xs_process_instance where id = ? for update")) {
                lock.setObject(1, processId);
                lock.executeQuery().close();
            }

            var result = assertTimeoutPreemptively(Duration.ofSeconds(5), instances::recover);

            assertEquals(new ApplicationInstanceDao.RecoveryResult(0, 0), result);
            connection.rollback();
        }
        assertEquals(new ApplicationInstanceDao.RecoveryResult(1, 0), instances.recover());
        assertNull(owner(processId));
    }

    @Test
    void heartbeatShouldUpdateOnlyInstanceRow() {
        var instances = new ApplicationInstanceDao(database);
        var instanceId = UUID.randomUUID();
        instances.register(instanceId, INSTANCE_TIMEOUT);
        insertReady(new UUID(0, 1));
        var before = processVersion(new UUID(0, 1));
        var expiryBefore = expiry(instanceId);

        assertTrue(instances.heartbeat(instanceId, Duration.ofMinutes(5)));

        assertEquals(before, processVersion(new UUID(0, 1)));
        assertTrue(expiry(instanceId).isAfter(expiryBefore));
    }

    @Test
    void heartbeatShouldReturnFalseForRemovedInstance() {
        assertFalse(new ApplicationInstanceDao(database).heartbeat(UUID.randomUUID(), INSTANCE_TIMEOUT));
    }

    private void expire(UUID instanceId) {
        database.update("update xs_application_instance set expires_at = current_timestamp - interval '1' second "
                + "where id = ?", instanceId);
    }

    private void insertReady(UUID id) {
        database.update(
                """
                        insert into xs_process_instance
                            (id, created_at, state, worker_id, status, type, next_run_at)
                        values (?, current_timestamp, 'STARTED', ?, 'ACTIVE', 'heartbeat_test', current_timestamp)
                        """,
                id, WORKER.name());
    }

    private UUID owner(UUID id) {
        return database.queryForObject("select lease_owner from xs_process_instance where id = ?",
                rs -> rs.getObject(1, UUID.class), id)
            .orElse(null);
    }

    private long processVersion(UUID id) {
        return database.queryForObject("select row_version from xs_process_instance where id = ?",
                rs -> rs.getLong(1), id)
            .orElseThrow();
    }

    private Instant expiry(UUID id) {
        return database.queryForObject("select expires_at from xs_application_instance where id = ?",
                rs -> rs.getTimestamp(1).toInstant(), id)
            .orElseThrow();
    }
}
