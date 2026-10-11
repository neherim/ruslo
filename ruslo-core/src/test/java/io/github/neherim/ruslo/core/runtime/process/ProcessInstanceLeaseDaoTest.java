package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.TestDatabases;
import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.definitions.SimpleOneStepProcessDefinition;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseDao;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceDao;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseLostException;
import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;
import io.github.neherim.ruslo.core.runtime.worker.WorkerTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessInstanceLeaseDaoTest extends BaseTest {
    private static final Instant NOW = Instant.parse("2099-07-20T10:15:30.123456Z");
    private WorkerTestFixture fixture;
    private ProcessInstanceDao processDao;
    private LeaseDao leaseDao;
    private UUID ownerId;

    @BeforeEach
    void setUpLeaseFixture() {
        var definition = new SimpleOneStepProcessDefinition();
        fixture = WorkerTestFixture.with(database, definition);
        processDao = fixture.dao();
        leaseDao = new LeaseDao(database, TestDatabases.dialect());
        ownerId = UUID.randomUUID();
        new ApplicationInstanceDao(database).register(ownerId, Duration.ofMinutes(1));
    }

    @Test
    void shouldLoadAndPersistClaimedProcessWithFencing() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "fenced-success");
        var claim = claim(process);

        var claimed = processDao.findClaimedWithLock(claim).orElseThrow();
        claimed.process().setVariable("result", "saved");

        processDao.updateAndReleaseLease(claimed);
        var stored = processDao.findById(process.getId()).orElseThrow();
        assertEquals("saved", stored.getVariable("result", String.class));
        assertEquals(1L, rowVersion(process.getId()));
        assertNull(leaseOwner(process.getId()));
    }

    @Test
    void shouldPersistClaimedProcessWithoutReleasingLease() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "retained-lease");
        var claim = claim(process);
        var claimed = processDao.findClaimedWithLock(claim).orElseThrow();
        claimed.process().setVariable("checkpoint", "saved");

        processDao.updateClaimed(claimed);
        var reloadedClaimed = processDao.findClaimedWithLock(claim).orElseThrow();
        reloadedClaimed.process().setVariable("secondCheckpoint", "saved");
        processDao.updateClaimed(reloadedClaimed);

        var stored = processDao.findById(process.getId()).orElseThrow();
        assertEquals("saved", stored.getVariable("checkpoint", String.class));
        assertEquals(2L, rowVersion(process.getId()));
        assertEquals(claim.ownerId(), leaseOwner(process.getId()));
    }

    @Test
    void retainingLeaseUpdateRejectsVersionConflict() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "retained-conflict");
        var claim = claim(process);
        var claimed = processDao.findClaimedWithLock(claim).orElseThrow();
        claimed.process().setVariable("stale", true);
        var concurrent = processDao.findById(process.getId()).orElseThrow();
        concurrent.setVariable("fresh", true);
        processDao.update(concurrent);

        assertThrows(LeaseLostException.class, () -> processDao.updateClaimed(claimed));
        assertNull(processDao.findById(process.getId()).orElseThrow().getVariable("stale", Boolean.class));
        assertTrue(processDao.findById(process.getId()).orElseThrow().getVariable("fresh", Boolean.class));
    }

    @Test
    void retainingLeaseUpdateRejectsReplacedOwner() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "retained-stale");
        var claim = claim(process);
        var claimed = processDao.findClaimedWithLock(claim).orElseThrow();
        claimed.process().setVariable("stale", true);
        database.update("update xs_process_instance set lease_owner = null where id = ?",
                process.getId());
        var replacementOwner = registerOwner();
        var replacement = claim(process, replacementOwner);

        assertThrows(LeaseLostException.class, () -> processDao.updateClaimed(claimed));
        assertNull(processDao.findById(process.getId()).orElseThrow().getVariable("stale", Boolean.class));
        assertEquals(replacement.ownerId(), leaseOwner(process.getId()));
    }

    @Test
    void shouldRejectStaleBusinessStateAfterVersionConflict() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "version-conflict");
        var claim = claim(process);
        var claimed = processDao.findClaimedWithLock(claim).orElseThrow();
        claimed.process().setVariable("stale", true);
        var concurrentProcess = processDao.findById(process.getId()).orElseThrow();
        concurrentProcess.setVariable("fresh", true);
        processDao.update(concurrentProcess);

        assertThrows(LeaseLostException.class, () -> processDao.updateAndReleaseLease(claimed));
        assertNull(processDao.findById(process.getId()).orElseThrow().getVariable("stale", Boolean.class));
        assertTrue(processDao.findById(process.getId()).orElseThrow().getVariable("fresh", Boolean.class));
        assertEquals(claim.ownerId(), leaseOwner(process.getId()));
    }

    @Test
    void shouldRejectOldOwnerAfterLeaseWasReclaimed() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "lease-lost");
        var oldClaim = claim(process);
        var staleProcess = processDao.findClaimedWithLock(oldClaim).orElseThrow();
        staleProcess.process().setVariable("stale", true);
        database.update("update xs_process_instance set lease_owner = null where id = ?",
                process.getId());
        var replacementOwner = registerOwner();
        var newClaim = claim(process, replacementOwner);

        assertThrows(LeaseLostException.class, () -> processDao.updateAndReleaseLease(staleProcess));
        assertNull(processDao.findById(process.getId()).orElseThrow().getVariable("stale", Boolean.class));
        assertEquals(newClaim.ownerId(), leaseOwner(process.getId()));
    }

    @Test
    void shouldUpdateAndIncrementVersionForUnleasedUpdate() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "normal-update");
        process.setVariable("updated", true);

        processDao.update(process);

        assertEquals(true, processDao.findById(process.getId()).orElseThrow().getVariable("updated", Boolean.class));
        assertEquals(1L, rowVersion(process.getId()));
    }

    @Test
    void ordinaryUpdateDoesNotClearForeignLease() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "foreign-lease");
        var claim = claim(process);
        var ordinary = processDao.findById(process.getId()).orElseThrow();
        ordinary.setVariable("ordinary", true);

        processDao.update(ordinary);
        assertTrue(processDao.findById(process.getId()).orElseThrow()
                .getVariable("ordinary", Boolean.class));
        assertEquals(claim.ownerId(), leaseOwner(process.getId()));
    }

    @Test
    void claimedProcessRejectsLeaseForAnotherProcessWithSameOwner() {
        var process = fixture.insertNewProcess(new SimpleOneStepProcessDefinition().processType(), "claimed-mismatch");
        var foreignLease = new ProcessLease(UUID.randomUUID(), ownerId);

        assertThrows(IllegalArgumentException.class, () -> new ClaimedProcess(process, foreignLease, 0));
    }

    private long rowVersion(UUID processId) {
        return database.queryForObject("select row_version from xs_process_instance where id = ?",
                rs -> rs.getLong(1), processId).orElseThrow();
    }

    private ProcessLease claim(ProcessInstance process) {
        return claim(process, ownerId);
    }

    private ProcessLease claim(
            ProcessInstance process, UUID claimOwner) {
        return leaseDao.claimReadyForWorker(claimOwner, process.getWorkerId().orElseThrow(), 1, NOW)
                .stream().findFirst().orElseThrow();
    }

    private UUID registerOwner() {
        var id = UUID.randomUUID();
        new ApplicationInstanceDao(database).register(id, Duration.ofMinutes(1));
        return id;
    }

    private UUID leaseOwner(UUID processId) {
        return database.queryForObject("select lease_owner from xs_process_instance where id = ?",
                rs -> rs.getObject(1, UUID.class), processId).orElse(null);
    }
}
