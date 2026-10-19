package io.github.neherim.ruslo.examples.cookbook.polling;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class PollingTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void pollsUntilReportIsReady() {
        var processId = processEngine.newProcess(ReportGenerationDefinition.TYPE)
            .variable(ReportGenerationDefinition.PERIOD, "2026-09")
            .start();
        emulator.runUntilStop(processId);

        // The first check runs initialDelay after entering the await, the next ones every poll interval.
        // The report service stub has the report ready by the third check.
        emulator.fastForwardTime(ReportGenerationDefinition.FIRST_CHECK_DELAY);
        assertTrue(emulator.runUntilStop(processId).state().is(ReportGenerationDefinition.State.WAITING_FOR_REPORT));
        emulator.fastForwardTime(ReportGenerationDefinition.POLL_INTERVAL);
        assertTrue(emulator.runUntilStop(processId).state().is(ReportGenerationDefinition.State.WAITING_FOR_REPORT));
        emulator.fastForwardTime(ReportGenerationDefinition.POLL_INTERVAL);
        var completed = emulator.runUntilStop(processId);

        assertTrue(completed.state().is(ReportGenerationDefinition.State.COMPLETED));
    }
}
