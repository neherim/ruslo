package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkerFactoryTest extends BaseTest {

    @Test
    void buildShouldCreateWorkerWithConfiguredLimits() {
        var workerId = new WorkerId("virtual-worker");
        var spec = WorkerSpec.builder(workerId)
            .maxConcurrency(2)
            .build();
        var factory = WorkerTestFixture.with(database).workerFactory();

        var worker = factory.build(spec);
        try {
            assertEquals(workerId, worker.getId());
            assertEquals(2, worker.getAvailableCapacity());
        } finally {
            worker.close();
        }
    }
}
