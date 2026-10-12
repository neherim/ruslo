package io.github.neherim.ruslo.examples.cookbook.ioworker;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The emulator runs steps in the test thread and does not reproduce routing to workers: the test checks the process
 * result, not which worker ran the step.
 */
@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class IoWorkerTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void exportsDocumentAndStoresFileUrl() {
        var processId = processEngine.newProcess(DocumentExportDefinition.TYPE)
            .variable(DocumentExportDefinition.DOCUMENT_ID, "invoice-42")
            .start();

        var completed = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.COMPLETED, completed.status());
        assertTrue(completed.state().is(DocumentExportDefinition.State.COMPLETED));
        assertEquals("https://files.example.com/invoice-42.pdf",
                completed.variables().get(DocumentExportDefinition.FILE_URL).orElseThrow());
    }
}
