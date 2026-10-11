package io.github.neherim.ruslo.core.runtime.worker.impl;

import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseLostException;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseReleaser;
import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;
import io.github.neherim.ruslo.core.runtime.process.ClaimedProcess;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.worker.HandlerException;
import io.github.neherim.ruslo.core.runtime.worker.StepErrorHandler;
import io.github.neherim.ruslo.core.runtime.worker.StepExecutor;
import io.github.neherim.ruslo.core.runtime.worker.Worker;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/**
 * Worker that executes steps of claimed processes under a lease.
 * <p>
 * In {@link WorkerSpec.ExecutionMode#TRANSACTIONAL} mode the step runs in one transaction with storing the new
 * process state and holds the row lock: if the transition fails to commit, every database change the step made in
 * that transaction is rolled back too. External calls in such a step lengthen the transaction.
 * <p>
 * In {@link WorkerSpec.ExecutionMode#NON_TRANSACTIONAL} mode the step runs outside a transaction, at least once:
 * after a failure even a successfully executed step may be called again, so external calls must be idempotent.
 * If the process was changed concurrently, the step result is discarded.
 * <p>
 * Every transition commits separately; while the process is ready and routed to this worker, execution continues
 * under the same lease.
 */
public class ProcessWorker implements Worker {
    private static final Logger log = LoggerFactory.getLogger(ProcessWorker.class);

    private final WorkerSpec spec;
    private final Database database;
    private final ProcessInstanceDao processInstanceDao;
    private final StepExecutor stepExecutor;
    private final StepErrorHandler stepErrorHandler;
    private final LeaseReleaser leaseReleaser;
    private final ProcessEngineMetrics metrics;
    private final BoundedExecutor executor;

    public ProcessWorker(WorkerSpec spec, ExecutorService executorService, boolean shutdownExecutor,
                         Database database,
                         ProcessInstanceDao processInstanceDao,
                         StepExecutor stepExecutor,
                         StepErrorHandler stepErrorHandler,
                         ProcessEngineMetrics metrics,
                         LeaseReleaser leaseReleaser,
                         Runnable completionHook) {
        this.spec = Objects.requireNonNull(spec);
        this.database = Objects.requireNonNull(database);
        this.processInstanceDao = Objects.requireNonNull(processInstanceDao);
        this.stepExecutor = Objects.requireNonNull(stepExecutor);
        this.stepErrorHandler = Objects.requireNonNull(stepErrorHandler);
        this.leaseReleaser = Objects.requireNonNull(leaseReleaser);
        this.metrics = Objects.requireNonNull(metrics);
        this.executor = new BoundedExecutor(spec.maxConcurrency(), executorService, shutdownExecutor,
            completionHook);
        metrics.registerWorkerMetrics(spec.workerId().name(), spec.maxConcurrency(), executor::getActiveTaskCount,
            () -> processInstanceDao.countReadyForWorker(spec.workerId()),
            () -> processInstanceDao.oldestReadyAgeForWorker(spec.workerId()));
    }

    @Override
    public WorkerSpec getSpec() {
        return spec;
    }

    @Override
    public int getAvailableCapacity() {
        return executor.getAvailableCapacity();
    }

    @Override
    public boolean tryExecute(ProcessLease lease) {
        Objects.requireNonNull(lease, "Lease must not be null");
        return executor.trySubmit(() -> execute(lease));
    }

    @Override
    public void close() {
        executor.close();
    }

    private void execute(ProcessLease lease) {
        try {
            try {
                executeSteps(lease);
            } catch (HandlerException ex) {
                stepErrorHandler.handleHandlerError(ex.getCause(), lease);
            }
        } catch (LeaseLostException ex) {
            onLeaseLost(ex, lease);
        } catch (RuntimeException ex) {
            recoverFromSystemError(ex, lease);
        }
    }

    private void executeSteps(ProcessLease lease) {
        if (spec.executionMode() == WorkerSpec.ExecutionMode.TRANSACTIONAL) {
            while (database.doInTransactionWithResult(() -> executeClaimedStep(
                processInstanceDao.findClaimedWithLock(lease).orElseThrow(() -> leaseLost(lease))))) {
                // The transition is committed and the process is ready on this worker: continue under the same lease.
            }
        } else {
            while (executeClaimedStep(processInstanceDao.findClaimed(lease).orElseThrow(() -> leaseLost(lease)))) {
                // The transition is committed and the process is ready on this worker: continue under the same lease.
            }
        }
    }

    /** Executes one handler and stores the process. Returns {@code true} if the lease is kept for the next step. */
    private boolean executeClaimedStep(ClaimedProcess claimed) {
        var process = claimed.process();
        var retainLease = isRunnableHere(process) && stepExecutor.executeNextProcessStep(process)
            && isRunnableHere(process);
        if (retainLease) {
            processInstanceDao.updateClaimed(claimed);
        } else {
            processInstanceDao.updateAndReleaseLease(claimed);
        }
        return retainLease;
    }

    private boolean isRunnableHere(ProcessInstance process) {
        return process.isTimeToExecute() && process.getWorkerId().filter(spec.workerId()::equals).isPresent();
    }

    private void recoverFromSystemError(RuntimeException ex, ProcessLease lease) {
        try {
            stepErrorHandler.handleSystemError(ex, lease);
        } catch (LeaseLostException lost) {
            onLeaseLost(lost, lease);
        } catch (RuntimeException recoveryError) {
            log.error("Unable to postpone process {} after system error", lease.processId(), recoveryError);
            leaseReleaser.release(lease);
        }
    }

    /**
     * Discards the step result: the lease was taken over or the process was changed concurrently.
     * In the second case this owner still holds the lease, so it is released.
     */
    private void onLeaseLost(LeaseLostException ex, ProcessLease lease) {
        metrics.recordLeaseLost();
        log.debug(ex.getMessage());
        leaseReleaser.release(lease);
    }

    private static LeaseLostException leaseLost(ProcessLease lease) {
        return new LeaseLostException(lease.processId());
    }
}
