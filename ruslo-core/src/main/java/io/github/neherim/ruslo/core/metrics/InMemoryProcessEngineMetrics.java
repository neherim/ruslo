package io.github.neherim.ruslo.core.metrics;

import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;


import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Keeps all {@link ProcessEngineMetrics} in memory and exposes the values for tests and local diagnostics. */
public class InMemoryProcessEngineMetrics implements ProcessEngineMetrics {
    // Process lifecycle metrics
    private final Map<ProcessTypeId, Long> processStartedCounts = new ConcurrentHashMap<>();
    private final Map<ProcessTypeId, Long> processCompletedCounts = new ConcurrentHashMap<>();
    private final Map<ProcessTypeId, Long> processFailedCounts = new ConcurrentHashMap<>();

    // Process step execution metrics
    private final Map<ProcessTypeId, Map<ProcessStateId, StepExecutionMetrics>> stepExecutionStats = new ConcurrentHashMap<>();
    private final AtomicLong totalStepsExecuted = new AtomicLong();
    private final AtomicLong totalStepsFailed = new AtomicLong();

    // Engine metrics
    private final AtomicLong systemErrorCount = new AtomicLong();
    private final Map<String, WorkerMetrics> workerMetrics = new ConcurrentHashMap<>();
    private final AtomicLong leaseLostCount = new AtomicLong();

    // Process count suppliers for gauge metrics
    private final Map<ProcessStatus, Supplier<Long>> processCountSuppliers = new ConcurrentHashMap<>();

    @Override
    public void recordProcessStarted(ProcessType processType) {
        processStartedCounts.merge(ProcessType.of(processType.name()), 1L, Long::sum);
    }

    @Override
    public void recordProcessCompleted(ProcessType processType) {
        processCompletedCounts.merge(ProcessType.of(processType.name()), 1L, Long::sum);
    }

    @Override
    public void recordProcessFailed(ProcessType processType) {
        processFailedCounts.merge(ProcessType.of(processType.name()), 1L, Long::sum);
    }

    @Override
    public void recordStepExecuted(ProcessType processType, ProcessState processState, Duration stepDuration) {
        totalStepsExecuted.incrementAndGet();
        stepExecutionStats
                .computeIfAbsent(ProcessType.of(processType.name()), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(ProcessState.of(processState.name()), k -> new StepExecutionMetrics())
                .updateStats(stepDuration.toMillis());
    }

    @Override
    public void recordStepFailed(ProcessType processType, ProcessState processState, Duration stepDuration) {
        totalStepsFailed.incrementAndGet();
    }

    @Override
    public void recordSystemError() {
        systemErrorCount.incrementAndGet();
    }

    @Override
    public void registerProcessCount(ProcessStatus status, Supplier<Long> supplier) {
        processCountSuppliers.put(status, supplier);
    }

    @Override
    public void registerWorkerMetrics(String workerId, int capacity, Supplier<Integer> activeTasksSupplier,
                                      Supplier<Long> readyProcessesSupplier,
                                      Supplier<Duration> lagSupplier) {
        workerMetrics.put(workerId, new WorkerMetrics(capacity, activeTasksSupplier, readyProcessesSupplier,
                lagSupplier));
    }

    @Override
    public void recordLeaseLost() {
        leaseLostCount.incrementAndGet();
    }

    // Getter methods for retrieving metrics

    public long getProcessStartedCount(ProcessType processType) {
        return processStartedCounts.getOrDefault(ProcessType.of(processType.name()), 0L);
    }

    public long getProcessCompletedCount(ProcessType processType) {
        return processCompletedCounts.getOrDefault(ProcessType.of(processType.name()), 0L);
    }

    public long getProcessFailedCount(ProcessType processType) {
        return processFailedCounts.getOrDefault(ProcessType.of(processType.name()), 0L);
    }

    public Map<ProcessTypeId, Long> getAllProcessStartedCounts() {
        return new HashMap<>(processStartedCounts);
    }

    public Map<ProcessTypeId, Long> getAllProcessCompletedCounts() {
        return new HashMap<>(processCompletedCounts);
    }

    public StepExecutionMetrics getStepExecutionMetrics(ProcessType processType, ProcessState processState) {
        Map<ProcessStateId, StepExecutionMetrics> stateStats = stepExecutionStats.get(ProcessType.of(processType.name()));
        if (stateStats != null) {
            return stateStats.get(ProcessState.of(processState.name()));
        }
        return null;
    }

    public Map<ProcessTypeId, Map<ProcessStateId, StepExecutionMetrics>> getAllStepExecutionMetrics() {
        return stepExecutionStats;
    }

    public long getSystemErrorCount() {
        return systemErrorCount.get();
    }

    public long getLeaseLostCount() {
        return leaseLostCount.get();
    }

    public long getTotalStepsExecuted() {
        return totalStepsExecuted.get();
    }

    public long getTotalStepsFailed() {
        return totalStepsFailed.get();
    }

    public long getTotalProcessesCompleted() {
        return processCompletedCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    public long getTotalProcessesStarted() {
        return processStartedCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    public long getTotalProcessesFailed() {
        return processFailedCounts.values().stream().mapToLong(Long::longValue).sum();
    }

    /** Returns the current number of processes in the status, or 0 if no gauge is registered for it. */
    public long getProcessCount(ProcessStatus status) {
        var supplier = processCountSuppliers.get(status);
        return supplier != null ? supplier.get() : 0L;
    }

    /**
     * Resets all collected metrics. Useful between test runs.
     */
    public void reset() {
        processStartedCounts.clear();
        processCompletedCounts.clear();
        processFailedCounts.clear();
        stepExecutionStats.clear();
        totalStepsExecuted.set(0);
        totalStepsFailed.set(0);
        systemErrorCount.set(0);
        leaseLostCount.set(0);
    }

    public Map<String, WorkerMetrics> getAllWorkerMetrics() {
        return new HashMap<>(workerMetrics);
    }

    public WorkerMetrics getWorkerMetrics(String workerId) {
        return workerMetrics.get(workerId);
    }

    public static class StepExecutionMetrics {
        public long executionCount = 0;
        public long totalDurationMillis = 0;
        public long maxDurationMillis = 0;
        public long minDurationMillis = Long.MAX_VALUE;

        public synchronized void updateStats(long durationMillis) {
            executionCount++;
            totalDurationMillis += durationMillis;
            maxDurationMillis = Math.max(maxDurationMillis, durationMillis);
            minDurationMillis = Math.min(minDurationMillis, durationMillis);
        }

        public long getExecutionCount() {
            return executionCount;
        }

        public long getTotalDurationMillis() {
            return totalDurationMillis;
        }

        public double getAverageDurationMillis() {
            return executionCount > 0 ? (double) totalDurationMillis / executionCount : 0.0;
        }

        public long getMaxDurationMillis() {
            return maxDurationMillis;
        }

        public long getMinDurationMillis() {
            return minDurationMillis == Long.MAX_VALUE ? 0 : minDurationMillis;
        }
    }

    public record WorkerMetrics(int capacity,
                                Supplier<Integer> activeTasksSupplier,
                                Supplier<Long> readyProcessesSupplier,
                                Supplier<Duration> lagSupplier) {

        public int getActiveTasks() {
            return activeTasksSupplier.get();
        }

        public long getReadyProcesses() {
            return readyProcessesSupplier.get();
        }

        public Duration getLag() {
            return lagSupplier.get();
        }
    }
}
