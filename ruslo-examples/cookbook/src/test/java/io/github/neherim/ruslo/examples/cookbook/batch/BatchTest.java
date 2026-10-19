package io.github.neherim.ruslo.examples.cookbook.batch;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class BatchTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void processesAllAccountsBatchByBatch() {
        var processId = processEngine.newProcess(BonusAccrualDefinition.TYPE)
            .variable(BonusAccrualDefinition.LAST_ACCOUNT_ID, 250L)
            .start();

        // Each batch is a separate transition: the cursor moves by the batch size
        assertEquals(100L, emulator.runOneStep(processId).variables().get(BonusAccrualDefinition.CURSOR).orElseThrow());
        assertEquals(200L, emulator.runOneStep(processId).variables().get(BonusAccrualDefinition.CURSOR).orElseThrow());
        var completed = emulator.runOneStep(processId);

        assertTrue(completed.state().is(BonusAccrualDefinition.State.COMPLETED));
        assertEquals(250L, completed.variables().get(BonusAccrualDefinition.CURSOR).orElseThrow());
    }
}
