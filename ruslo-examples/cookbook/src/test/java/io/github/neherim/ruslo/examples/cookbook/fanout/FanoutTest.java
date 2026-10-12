package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class FanoutTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @MockitoSpyBean
    private SplitFileStep splitFileStep;

    @Test
    void completesWhenAllPartsReported() {
        var parentId = startImport(250);
        var waiting = emulator.runUntilStop(parentId);
        assertTrue(waiting.state().is(FileImportDefinition.State.WAITING_FOR_PARTS));

        var parts = partsOf(waiting);
        assertEquals(3, parts.size());
        parts.forEach(part -> assertTrue(emulator.runUntilStop(part.id())
            .state()
            .is(FileImportPartDefinition.State.IMPORTED)));

        var completed = emulator.runUntilStop(parentId);

        assertTrue(completed.state().is(FileImportDefinition.State.COMPLETED));
        assertEquals(250,
                completed.variables().get(FileImportDefinition.IMPORTED_ROWS).orElseThrow());
    }

    @Test
    void savesNoPartsWhenSplittingFails() {
        // The step fails after starting the parts: the step transaction rolls back together with them
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("Storage is unavailable");
        }).when(splitFileStep).execute(any());

        var failed = emulator.runUntilStop(startImport(250));

        assertEquals(ProcessStatus.FAILED, failed.status());
        assertTrue(partsOf(failed).isEmpty());
    }

    private UUID startImport(int rowCount) {
        return processEngine.newProcess(FileImportDefinition.TYPE)
            .variable(FileImportDefinition.FILE_NAME, "customers.csv")
            .variable(FileImportDefinition.ROW_COUNT, rowCount)
            .start();
    }

    /** Finds parts by the parent's correlation id: the split step passes it to every part. */
    private List<ProcessInstanceSummary> partsOf(ProcessInstanceDetails parent) {
        return processEngine.processes()
            .byType(FileImportPartDefinition.TYPE)
            .byCorrelationId(parent.correlationId())
            .list();
    }
}
