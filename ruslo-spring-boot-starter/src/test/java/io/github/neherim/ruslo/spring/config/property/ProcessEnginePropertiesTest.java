package io.github.neherim.ruslo.spring.config.property;

import io.github.neherim.ruslo.core.api.definition.WorkerId;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessEnginePropertiesTest {

    @Test
    void getWorkerSpecsShouldMapDefaultAliasToDefaultWorkerId() {
        var properties = new ProcessEngineProperties();
        properties.setWorkers(Map.of("default", new WorkerProperties()));

        var workerSpecs = properties.getWorkerSpecs();

        assertEquals(1, workerSpecs.size());
        assertEquals(WorkerId.DEFAULT, workerSpecs.getFirst().workerId());
    }

    @Test
    void getWorkerSpecsShouldRejectDefaultAliasWhenDefaultWorkerIsAlsoConfigured() {
        var properties = new ProcessEngineProperties();
        properties.setWorkers(Map.of(
                "DEFAULT", new WorkerProperties(),
                "default", new WorkerProperties()));

        var exception = assertThrows(IllegalArgumentException.class, properties::getWorkerSpecs);

        assertEquals("ruslo.workers must not define both DEFAULT and default", exception.getMessage());
    }
}
