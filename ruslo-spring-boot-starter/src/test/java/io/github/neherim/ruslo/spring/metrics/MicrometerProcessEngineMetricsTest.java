package io.github.neherim.ruslo.spring.metrics;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MicrometerProcessEngineMetricsTest {
    private static final ProcessType TYPE = ProcessType.of("ORDER");

    @Test
    void shouldRecordProcessAndStepMetrics() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerProcessEngineMetrics(registry);

        metrics.recordProcessStarted(TYPE);
        metrics.recordProcessCompleted(TYPE);
        metrics.recordProcessFailed(TYPE);
        metrics.recordStepExecuted(TYPE, State.PAY, Duration.ofMillis(5));
        metrics.recordStepExecuted(TYPE, State.PAY, Duration.ofMillis(7));
        metrics.recordStepFailed(TYPE, State.PAY, Duration.ofMillis(3));
        metrics.recordSystemError();

        assertEquals(1.0, registry.get("ruslo.processes.started").tag("type", "ORDER").counter().count());
        assertEquals(1.0, registry.get("ruslo.processes.completed").tag("type", "ORDER").counter().count());
        assertEquals(1.0, registry.get("ruslo.processes.failed").tag("type", "ORDER").counter().count());
        assertEquals(2L, registry.get("ruslo.processes.steps.duration")
            .tags("type", "ORDER", "state", "PAY", "outcome", "success")
            .timer()
            .count());
        assertEquals(1L, registry.get("ruslo.processes.steps.duration")
            .tags("type", "ORDER", "state", "PAY", "outcome", "error")
            .timer()
            .count());
        assertEquals(1.0, registry.get("ruslo.system.errors").counter().count());
    }

    @Test
    void shouldRecordLeaseLost() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerProcessEngineMetrics(registry);

        metrics.recordLeaseLost();

        assertEquals(1.0, registry.counter("ruslo.lease.lost").count());
    }

    @Test
    void gaugesShouldSurviveGarbageCollection() {
        var registry = new SimpleMeterRegistry();
        var metrics = new MicrometerProcessEngineMetrics(registry);
        metrics.registerProcessCount(ProcessStatus.ACTIVE, () -> 3L);
        metrics.registerProcessCount(ProcessStatus.FAILED, () -> 1L);
        metrics.registerWorkerMetrics("DEFAULT", 8, () -> 2, () -> 5L, () -> Duration.ofSeconds(4));

        System.gc();

        assertEquals(3.0, registry.get("ruslo.processes").tag("status", "ACTIVE").gauge().value());
        assertEquals(1.0, registry.get("ruslo.processes").tag("status", "FAILED").gauge().value());
        assertEquals(8.0, registry.get("ruslo.worker.capacity").tag("worker", "DEFAULT").gauge().value());
        assertEquals(2.0, registry.get("ruslo.worker.active").tag("worker", "DEFAULT").gauge().value());
        assertEquals(5.0, registry.get("ruslo.worker.ready").tag("worker", "DEFAULT").gauge().value());
        assertEquals(4.0, registry.get("ruslo.worker.lag")
            .tag("worker", "DEFAULT")
            .timeGauge()
            .value(TimeUnit.SECONDS));
    }

    private enum State implements ProcessState {
        PAY
    }
}
