package io.github.neherim.ruslo.spring.metrics;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.micrometer.core.instrument.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Micrometer-based implementation of {@link ProcessEngineMetrics}.
 * <p>
 * This implementation uses Micrometer's {@link MeterRegistry} to collect and report
 * process engine metrics. Metrics are automatically exposed through the configured
 * Micrometer backend (e.g., Prometheus, Graphite, etc.).
 * <p>
 * Metrics collected:
 * <ul>
 *     <li>{@code ruslo.processes.started} - Counter with tag {@code type}</li>
 *     <li>{@code ruslo.processes.completed} - Counter with tag {@code type}</li>
 *     <li>{@code ruslo.processes.failed} - Counter with tag {@code type}</li>
 *     <li>{@code ruslo.processes.steps.duration} - Timer with tags {@code type}, {@code state}
 *     and {@code outcome} (success, error)</li>
 *     <li>{@code ruslo.processes} - Gauge with tag {@code status} (ACTIVE, FAILED)</li>
 *     <li>{@code ruslo.system.errors} - Counter</li>
 *     <li>{@code ruslo.worker.capacity} - Gauge with tag {@code worker}</li>
 *     <li>{@code ruslo.worker.active} - Gauge with tag {@code worker}</li>
 *     <li>{@code ruslo.worker.ready} - Gauge with tag {@code worker}</li>
 *     <li>{@code ruslo.worker.lag} - TimeGauge with tag {@code worker}</li>
 *     <li>{@code ruslo.lease.lost} - Counter</li>
 * </ul>
 * Gauges hold strong references to their suppliers, so the values survive garbage collection.
 */
public class MicrometerProcessEngineMetrics implements ProcessEngineMetrics {

    private static final String METRIC_PREFIX = "ruslo";

    private final MeterRegistry registry;
    private final Map<String, Counter> processStartedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> processCompletedCounters = new ConcurrentHashMap<>();
    private final Map<String, Counter> processFailedCounters = new ConcurrentHashMap<>();
    private final Map<Tags, Timer> stepTimers = new ConcurrentHashMap<>();
    private final Counter systemErrorsCounter;
    private final Counter leaseLostCounter;

    /**
     * Creates a new instance of MicrometerProcessEngineMetrics.
     *
     * @param registry the Micrometer registry to use for metric registration
     */
    public MicrometerProcessEngineMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.systemErrorsCounter = counter("system.errors",
                "Number of engine errors not caused by handler code, e.g. database failures");
        this.leaseLostCounter = counter("lease.lost",
                "Number of step results discarded due to lost lease or concurrent process change");
    }

    @Override
    public void recordProcessStarted(ProcessType processType) {
        processCounter(processStartedCounters, "started", processType).increment();
    }

    @Override
    public void recordProcessCompleted(ProcessType processType) {
        processCounter(processCompletedCounters, "completed", processType).increment();
    }

    @Override
    public void recordProcessFailed(ProcessType processType) {
        processCounter(processFailedCounters, "failed", processType).increment();
    }

    @Override
    public void recordStepExecuted(ProcessType processType, ProcessState processState, Duration stepDuration) {
        stepTimer(processType, processState, "success").record(stepDuration);
    }

    @Override
    public void recordStepFailed(ProcessType processType, ProcessState processState, Duration stepDuration) {
        stepTimer(processType, processState, "error").record(stepDuration);
    }

    @Override
    public void recordSystemError() {
        systemErrorsCounter.increment();
    }

    @Override
    public void registerProcessCount(ProcessStatus status, Supplier<Long> supplier) {
        Gauge.builder(METRIC_PREFIX + ".processes", supplier::get)
            .description("Number of processes in status")
            .tag("status", status.name())
            .register(registry);
    }

    @Override
    public void registerWorkerMetrics(
            String workerId,
            int capacity,
            Supplier<Integer> activeTasksSupplier,
            Supplier<Long> readyProcessesSupplier,
            Supplier<Duration> lagSupplier
    ) {
        Gauge.builder(METRIC_PREFIX + ".worker.capacity", () -> capacity)
            .description("Maximum number of concurrently executing tasks")
            .tag("worker", workerId)
            .register(registry);
        Gauge.builder(METRIC_PREFIX + ".worker.active", activeTasksSupplier::get)
            .description("Number of executing tasks")
            .tag("worker", workerId)
            .register(registry);
        Gauge.builder(METRIC_PREFIX + ".worker.ready", readyProcessesSupplier::get)
            .description("Number of ready processes not yet claimed by any instance")
            .tag("worker", workerId)
            .register(registry);
        TimeGauge.builder(METRIC_PREFIX + ".worker.lag",
                () -> lagSupplier.get().toNanos(), TimeUnit.NANOSECONDS)
            .description("How long the oldest ready process has been waiting to be claimed")
            .tag("worker", workerId)
            .register(registry);
    }

    @Override
    public void recordLeaseLost() {
        leaseLostCounter.increment();
    }

    private Counter processCounter(Map<String, Counter> counters, String event, ProcessType processType) {
        return counters.computeIfAbsent(processType.name(),
                type -> Counter.builder(METRIC_PREFIX + ".processes." + event)
                    .description("Number of processes " + event)
                    .tag("type", type)
                    .register(registry));
    }

    private Timer stepTimer(ProcessType processType, ProcessState processState, String outcome) {
        var tags = Tags.of("type", processType.name(), "state", processState.name(), "outcome", outcome);
        return stepTimers.computeIfAbsent(tags, t -> Timer.builder(METRIC_PREFIX + ".processes.steps.duration")
            .description("Duration of process step handler execution")
            .tags(t)
            .register(registry));
    }

    private Counter counter(String name, String description) {
        return Counter.builder(METRIC_PREFIX + "." + name)
            .description(description)
            .register(registry);
    }

}
