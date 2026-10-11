package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.WorkerId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkerSpecTest {

    @Test
    void shouldUseSafeDefaults() {
        var spec = WorkerSpec.builder(new WorkerId("worker")).build();

        assertEquals(10, spec.maxConcurrency());
        assertNull(spec.customExecutor());
    }

    @Test
    void shouldRejectInvalidLimits() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> WorkerSpec.builder(new WorkerId("worker")).maxConcurrency(0).build()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> WorkerSpec.builder(new WorkerId("worker")).maxConcurrency(-1).build())
        );
    }
}
