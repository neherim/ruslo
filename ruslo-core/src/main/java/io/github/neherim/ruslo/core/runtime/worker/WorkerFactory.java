package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseReleaser;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.worker.impl.ProcessWorker;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;

import java.util.Objects;
import java.util.concurrent.Executors;

/** Creates process workers from {@link WorkerSpec}. */
public class WorkerFactory {
    private final Database database;
    private final ProcessInstanceDao processInstanceDao;
    private final StepExecutor stepExecutor;
    private final StepErrorHandler stepErrorHandler;
    private final ProcessEngineMetrics processEngineMetrics;
    private final LeaseReleaser leaseReleaser;
    private final Runnable completionHook;

    /**
     * Creates a worker factory.
     *
     * @param completionHook called when a worker frees capacity
     */
    public WorkerFactory(
            Database database,
            ProcessInstanceDao processInstanceDao,
            StepExecutor stepExecutor,
            StepErrorHandler stepErrorHandler,
            ProcessEngineMetrics processEngineMetrics,
            LeaseReleaser leaseReleaser,
            Runnable completionHook
    ) {
        this.database = Objects.requireNonNull(database);
        this.processInstanceDao = Objects.requireNonNull(processInstanceDao);
        this.stepExecutor = Objects.requireNonNull(stepExecutor);
        this.stepErrorHandler = Objects.requireNonNull(stepErrorHandler);
        this.processEngineMetrics = Objects.requireNonNull(processEngineMetrics);
        this.leaseReleaser = Objects.requireNonNull(leaseReleaser);
        this.completionHook = Objects.requireNonNull(completionHook);
    }

    /**
     * Creates a worker. Without a custom executor in the spec, the worker gets a virtual-thread-per-task executor
     * and shuts it down on close.
     */
    public Worker build(WorkerSpec spec) {
        var customExecutor = spec.customExecutor();
        var executorService = customExecutor != null
                ? customExecutor
                : Executors.newThreadPerTaskExecutor(
                        Thread.ofVirtual().name("process-worker-" + spec.workerId().name() + "-", 0).factory());
        return new ProcessWorker(spec, executorService, customExecutor == null, database, processInstanceDao,
                stepExecutor, stepErrorHandler, processEngineMetrics, leaseReleaser, completionHook);
    }
}
