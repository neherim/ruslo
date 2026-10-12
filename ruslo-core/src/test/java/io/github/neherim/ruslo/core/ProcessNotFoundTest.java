package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.exception.ProcessNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessNotFoundTest extends BaseTest {

    @Test
    void runtimeOperationsShouldThrowProcessNotFoundException() {
        var processEngine = newProcessEngine();
        var processId = UUID.randomUUID();

        assertAll(
                () -> assertThrows(ProcessNotFoundException.class,
                        () -> processEngine.resumeFailedProcess(processId)),
                () -> assertThrows(ProcessNotFoundException.class,
                        () -> emulator(processEngine).runOneStep(processId))
        );
    }

    @Test
    void emulatorShouldThrowProcessNotFoundException() {
        var processId = UUID.randomUUID();
        var emulator = emulator(newProcessEngine());

        var exception = assertThrows(ProcessNotFoundException.class,
                () -> emulator.runUntilStop(processId));

        assertEquals("Process " + processId + " not found", exception.getMessage());
    }
}
