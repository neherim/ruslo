package io.github.neherim.ruslo.core.spi;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Engine metrics sink. All methods are no-ops by default; exceptions thrown by an implementation are logged and
 * ignored.
 */
public interface ProcessEngineMetrics {

    /** Called after a process start commits. */
    default void recordProcessStarted(ProcessType processType) {
    }

    /** Called after the transition to a terminal state commits. */
    default void recordProcessCompleted(ProcessType processType) {
    }

    /** Called after the transition to {@code FAILED} commits. */
    default void recordProcessFailed(ProcessType processType) {
    }

    /**
     * Called when a step, message or timer handler returns, before the transition commits, so rolled-back
     * attempts are counted too.
     */
    default void recordStepExecuted(ProcessType processType, ProcessState processState, Duration stepDuration) {
    }

    /** Called when a step, message or timer handler throws. */
    default void recordStepFailed(ProcessType processType, ProcessState processState, Duration stepDuration) {
    }

    /**
     * Called on an engine failure not caused by user code: process execution, dispatch, lease release, heartbeat
     * or instance recovery (usually the database is unavailable).
     */
    default void recordSystemError() {
    }

    /** Registers a gauge of {@code ACTIVE} or {@code FAILED} process count. */
    default void registerProcessCount(ProcessStatus status, Supplier<Long> supplier) {
    }

    /**
     * Registers worker gauges.
     *
     * @param capacity               maximum concurrent tasks
     * @param readyProcessesSupplier ready processes of the worker not yet claimed
     * @param lagSupplier            how long the oldest of them has been waiting; zero if there are none
     */
    default void registerWorkerMetrics(
            String workerId,
            int capacity,
            Supplier<Integer> activeTasksSupplier,
            Supplier<Long> readyProcessesSupplier,
            Supplier<Duration> lagSupplier
    ) {
    }

    /** Called when a task result is discarded because the lease was lost or the process changed concurrently. */
    default void recordLeaseLost() {
    }
}
