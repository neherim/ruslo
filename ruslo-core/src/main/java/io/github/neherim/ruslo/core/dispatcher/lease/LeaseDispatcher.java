package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.dispatcher.ProcessDispatcher;
import io.github.neherim.ruslo.core.runtime.worker.Worker;
import io.github.neherim.ruslo.core.runtime.worker.WorkerRegistry;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Dispatcher that hands out short individual leases within the local worker capacity.
 *
 * <p>While the instance is not registered (e.g. after a lost registration and before re-registration), a claim
 * returns nothing: the same SQL checks the registration.
 */
public final class LeaseDispatcher implements ProcessDispatcher {
    private static final Logger log = LoggerFactory.getLogger(LeaseDispatcher.class);

    private final Supplier<UUID> ownerId;
    private final LeaseDao leaseDao;
    private final LeaseReleaser leaseReleaser;
    private final WorkerRegistry workerRegistry;
    private final ProcessEngineMetrics metrics;
    private final Clock clock;

    /**
     * Creates a lease dispatcher.
     *
     * @param ownerId supplies the current registration id of this instance
     */
    public LeaseDispatcher(Supplier<UUID> ownerId, LeaseDao leaseDao, LeaseReleaser leaseReleaser,
                           WorkerRegistry workerRegistry, ProcessEngineMetrics metrics, Clock clock) {
        this.ownerId = Objects.requireNonNull(ownerId, "Lease owner id supplier must not be null");
        this.leaseDao = Objects.requireNonNull(leaseDao, "Lease DAO must not be null");
        this.leaseReleaser = Objects.requireNonNull(leaseReleaser, "Lease releaser must not be null");
        this.workerRegistry = Objects.requireNonNull(workerRegistry, "Worker registry must not be null");
        this.metrics = Objects.requireNonNull(metrics, "Metrics must not be null");
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
    }

    /**
     * Retries failed lease releases, then claims for each worker no more processes than its free capacity and
     * submits them. A failure of one worker does not affect the others.
     */
    @Override
    public boolean dispatch() {
        leaseReleaser.retryPending();
        var anySubmitted = false;
        for (var worker : workerRegistry.getAllWorkers()) {
            try {
                anySubmitted |= dispatchWorker(worker);
            } catch (RuntimeException ex) {
                metrics.recordSystemError();
                log.error("Error dispatching processes to worker {}", worker.getId(), ex);
            }
        }
        return anySubmitted;
    }

    /**
     * Returns the nearest {@code available_at} among workers with free capacity: a worker without capacity wakes
     * the scheduler itself once it frees up.
     */
    @Override
    public Optional<Instant> nextAvailableAt() {
        Instant next = null;
        for (var worker : workerRegistry.getAllWorkers()) {
            if (worker.getAvailableCapacity() <= 0) {
                continue;
            }
            var workerNext = leaseDao.findNextAvailableAt(worker.getId()).orElse(null);
            if (workerNext != null && (next == null || workerNext.isBefore(next))) {
                next = workerNext;
            }
        }
        return Optional.ofNullable(next);
    }

    private boolean dispatchWorker(Worker worker) {
        var capacity = worker.getAvailableCapacity();
        if (capacity <= 0) {
            return false;
        }
        var now = clock.instant();
        var anySubmitted = false;
        for (var lease : leaseDao.claimReadyForWorker(ownerId.get(), worker.getId(), capacity, now)) {
            anySubmitted |= submit(worker, lease);
        }
        return anySubmitted;
    }

    private boolean submit(Worker worker, ProcessLease lease) {
        boolean accepted;
        try {
            accepted = worker.tryExecute(lease);
        } catch (RuntimeException ex) {
            log.warn("Worker {} failed to accept process {}", worker.getId(), lease.processId(), ex);
            accepted = false;
        }
        if (!accepted) {
            leaseReleaser.release(lease);
        }
        return accepted;
    }
}
