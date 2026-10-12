package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.api.view.ApplicationInstanceInfo;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.UUIDGenerator;
import io.github.neherim.ruslo.core.utils.thread.NamedThreadFactory;
import io.github.neherim.ruslo.core.utils.thread.ThreadUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Registers this engine instance and keeps its registration alive.
 *
 * <p>Every start and every re-registration gets a new id, used as the lease owner. An old id is never reused:
 * a claim does not change {@code row_version}, so a new claim under the old owner would let a still running task
 * pass fencing.
 */
public final class ApplicationInstanceLifecycle implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ApplicationInstanceLifecycle.class);

    private final Duration heartbeatInterval;
    private final Duration instanceTimeout;
    private final ApplicationInstanceDao instanceDao;
    private final ProcessEngineMetrics metrics;
    private ScheduledExecutorService scheduler;
    private volatile UUID ownerId;

    /**
     * Creates the instance lifecycle.
     *
     * @param heartbeatInterval interval of heartbeat and recovery
     * @param instanceTimeout   time after which an instance without a heartbeat is considered dead
     */
    public ApplicationInstanceLifecycle(
            Duration heartbeatInterval,
            Duration instanceTimeout,
            ApplicationInstanceDao instanceDao,
            ProcessEngineMetrics metrics
    ) {
        this.heartbeatInterval = Objects.requireNonNull(heartbeatInterval, "Heartbeat interval must not be null");
        this.instanceTimeout = Objects.requireNonNull(instanceTimeout, "Instance timeout must not be null");
        this.instanceDao = Objects.requireNonNull(instanceDao, "Application instance DAO must not be null");
        this.metrics = Objects.requireNonNull(metrics, "Metrics must not be null");
    }

    /** Registers the instance under a new id, runs recovery and starts the heartbeat. */
    public synchronized void start() {
        if (scheduler != null) throw new IllegalStateException("Application instance lifecycle already started");
        var newId = UUIDGenerator.generate();
        instanceDao.register(newId, instanceTimeout);
        ownerId = newId;
        recover();
        scheduler = Executors.newSingleThreadScheduledExecutor(new NamedThreadFactory("process-instance-lifecycle"));
        scheduler.scheduleWithFixedDelay(this::tick,
                heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Returns the current registration id, used as the lease owner. */
    public UUID ownerId() {
        return ownerId;
    }

    /** Returns registered engine instances; the registration of this engine is marked as current. */
    public List<ApplicationInstanceInfo> instances() {
        return instanceDao.list(ownerId);
    }

    @Override
    public synchronized void close() {
        if (scheduler == null) return;
        ThreadUtils.immediateShutdown(scheduler, Duration.ofSeconds(10));
        scheduler = null;
        instanceDao.unregister(ownerId);
    }

    private void tick() {
        try {
            if (!instanceDao.heartbeat(ownerId, instanceTimeout)) {
                reRegister();
            }
        } catch (Throwable ex) {
            metrics.recordSystemError();
            log.error("Application instance heartbeat failed", ex);
        }
        recover();
    }

    /**
     * Registers the instance under a new id after recovery on another instance deleted the old registration.
     * That recovery releases the leases of the old owner, and its tasks discard their results by fencing.
     */
    private void reRegister() {
        var lostId = ownerId;
        var newId = UUIDGenerator.generate();
        instanceDao.register(newId, instanceTimeout);
        ownerId = newId;
        log.warn("Application instance registration {} was removed; re-registered as {}", lostId, newId);
    }

    private void recover() {
        try {
            var result = instanceDao.recover();
            if (result.removedInstances() > 0 || result.releasedLeases() > 0) {
                log.warn("Removed {} expired application instances and released {} leases",
                        result.removedInstances(), result.releasedLeases());
            }
        } catch (Throwable ex) {
            metrics.recordSystemError();
            log.error("Application instance recovery failed", ex);
        }
    }
}
