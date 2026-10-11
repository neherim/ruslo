package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Releases leases and retries failed releases.
 *
 * <p>Recovery does not release the leases of a live instance, so a lease that failed to be released (usually because
 * the database is unavailable) is queued and retried at the start of every dispatch cycle.
 */
public final class LeaseReleaser {
    private static final Logger log = LoggerFactory.getLogger(LeaseReleaser.class);

    private final LeaseDao leaseDao;
    private final ProcessEngineMetrics metrics;
    private final Queue<ProcessLease> pending = new ConcurrentLinkedQueue<>();

    public LeaseReleaser(LeaseDao leaseDao, ProcessEngineMetrics metrics) {
        this.leaseDao = Objects.requireNonNull(leaseDao, "Lease DAO must not be null");
        this.metrics = Objects.requireNonNull(metrics, "Metrics must not be null");
    }

    /** Releases the lease if its owner still holds it; on failure queues it for retry. */
    public void release(ProcessLease lease) {
        if (!tryRelease(lease)) {
            pending.add(lease);
        }
    }

    /** Retries releasing the leases that failed before. */
    public void retryPending() {
        pending.removeIf(this::tryRelease);
    }

    private boolean tryRelease(ProcessLease lease) {
        try {
            if (!leaseDao.release(lease)) {
                log.debug("Lease release fenced for process {} owner {}", lease.processId(), lease.ownerId());
            }
            return true;
        } catch (RuntimeException ex) {
            metrics.recordSystemError();
            log.warn("Unable to release lease for process {} owner {}", lease.processId(), lease.ownerId(), ex);
            return false;
        }
    }
}
