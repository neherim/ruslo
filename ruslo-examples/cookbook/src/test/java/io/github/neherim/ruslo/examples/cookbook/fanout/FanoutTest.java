package io.github.neherim.ruslo.examples.cookbook.fanout;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceSummary;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class FanoutTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void completesWhenAllPartsReported() {
        var parentId = startImport(3);
        var waiting = emulator.runUntilStop(parentId);
        assertTrue(waiting.state().is(FileImportDefinition.State.WAITING_FOR_PARTS));

        var parts = partsOf(waiting);
        assertEquals(3, parts.size());
        parts.forEach(part -> assertTrue(emulator.runUntilStop(part.id())
            .state()
            .is(FileImportPartDefinition.State.IMPORTED)));

        var completed = emulator.runUntilStop(parentId);

        assertTrue(completed.state().is(FileImportDefinition.State.COMPLETED));
    }

    private UUID startImport(int partCount) {
        return processEngine.newProcess(FileImportDefinition.TYPE)
            .variable(FileImportDefinition.PART_COUNT, partCount)
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
