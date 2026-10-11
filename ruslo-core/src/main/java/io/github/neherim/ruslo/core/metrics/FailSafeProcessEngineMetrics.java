package io.github.neherim.ruslo.core.metrics;

import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/** Isolates engine execution from failures of a user metrics implementation. */
public final class FailSafeProcessEngineMetrics implements ProcessEngineMetrics {
    private static final Logger log = LoggerFactory.getLogger(FailSafeProcessEngineMetrics.class);

    private final ProcessEngineMetrics delegate;

    private FailSafeProcessEngineMetrics(ProcessEngineMetrics delegate) {
        this.delegate = delegate;
    }

    /** Returns metrics that log and suppress runtime exceptions of {@code metrics}. */
    public static ProcessEngineMetrics wrap(ProcessEngineMetrics metrics) {
        Objects.requireNonNull(metrics, "Metrics must not be null");
        return metrics instanceof FailSafeProcessEngineMetrics ? metrics : new FailSafeProcessEngineMetrics(metrics);
    }

    @Override
    public void recordProcessStarted(ProcessType processType) {
        safely("recordProcessStarted", () -> delegate.recordProcessStarted(processType));
    }

    @Override
    public void recordProcessCompleted(ProcessType processType) {
        safely("recordProcessCompleted", () -> delegate.recordProcessCompleted(processType));
    }

    @Override
    public void recordProcessFailed(ProcessType processType) {
        safely("recordProcessFailed", () -> delegate.recordProcessFailed(processType));
    }

    @Override
    public void recordStepExecuted(ProcessType processType, ProcessState processState, Duration stepDuration) {
        safely("recordStepExecuted", () -> delegate.recordStepExecuted(processType, processState, stepDuration));
    }

    @Override
    public void recordStepFailed(ProcessType processType, ProcessState processState, Duration stepDuration) {
        safely("recordStepFailed", () -> delegate.recordStepFailed(processType, processState, stepDuration));
    }

    @Override
    public void recordSystemError() {
        safely("recordSystemError", delegate::recordSystemError);
    }

    @Override
    public void registerProcessCount(ProcessStatus status, Supplier<Long> supplier) {
        safely("registerProcessCount", () -> delegate.registerProcessCount(status, supplier));
    }

    @Override
    public void registerWorkerMetrics(String workerId, int capacity, Supplier<Integer> activeTasksSupplier,
                                      Supplier<Long> readyProcessesSupplier,
                                      Supplier<Duration> lagSupplier) {
        safely("registerWorkerMetrics", () -> delegate.registerWorkerMetrics(workerId, capacity,
                activeTasksSupplier, readyProcessesSupplier, lagSupplier));
    }

    @Override
    public void recordLeaseLost() {
        safely("recordLeaseLost", delegate::recordLeaseLost);
    }

    private void safely(String operation, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("Process engine metrics operation {} failed", operation, ex);
        }
    }
}
