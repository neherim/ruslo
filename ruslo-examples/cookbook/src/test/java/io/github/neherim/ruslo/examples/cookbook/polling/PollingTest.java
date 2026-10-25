package io.github.neherim.ruslo.examples.cookbook.polling;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        assertEquals(ReportGenerationDefinition.State.WAITING_FOR_REPORT.id(),
                emulator.runUntilStop(processId).state());
        emulator.fastForwardTime(ReportGenerationDefinition.POLL_INTERVAL);
        assertEquals(ReportGenerationDefinition.State.WAITING_FOR_REPORT.id(),
                emulator.runUntilStop(processId).state());
        emulator.fastForwardTime(ReportGenerationDefinition.POLL_INTERVAL);
        var completed = emulator.runUntilStop(processId);

        assertEquals(ReportGenerationDefinition.State.COMPLETED.id(), completed.state());
    }
}
