package io.github.neherim.ruslo.spring.config.property;

import io.github.neherim.ruslo.core.api.WorkerSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerPropertiesTest {

    @Test
    void toWorkerSpecShouldMapExecutionLimits() {
        var properties = new WorkerProperties();
        properties.setMaxConcurrency(4);

        var spec = properties.toWorkerSpec("IO");

        assertEquals(4, spec.maxConcurrency());
    }

    @Test
    void toWorkerSpecShouldMapExecutionMode() {
        var properties = new WorkerProperties();
        properties.setExecutionMode(WorkerSpec.ExecutionMode.NON_TRANSACTIONAL);

        var spec = properties.toWorkerSpec("IO");

        assertEquals(WorkerSpec.ExecutionMode.NON_TRANSACTIONAL, spec.executionMode());
    }

    @Test
    void toWorkerSpecShouldUseSafeDefaults() {
        var spec = new WorkerProperties().toWorkerSpec("IO");

        assertEquals(WorkerSpec.ExecutionMode.TRANSACTIONAL, spec.executionMode());
        assertEquals(10, spec.maxConcurrency());
    }
}
