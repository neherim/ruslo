package io.github.neherim.ruslo.examples.cookbook.polling;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class PollingTest {
    private static final String JOB_ID = "job-1";
    private static final ReportStatus IN_PROGRESS = new ReportStatus(ReportStatus.State.IN_PROGRESS, null);

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @MockitoBean
    private ReportService reportService;

    @Test
    void pollsUntilReportIsReady() {
        when(reportService.requestReport(any())).thenReturn(JOB_ID);
        when(reportService.checkStatus(JOB_ID)).thenReturn(
                IN_PROGRESS,
                new ReportStatus(ReportStatus.State.READY, "https://reports.example.com/job-1.csv"));
        var processId = startReport();
        emulator.runUntilStop(processId);

        // The first check runs initialDelay after entering the await: the report is not ready yet
        emulator.fastForwardTime(ReportGenerationDefinition.FIRST_CHECK_DELAY);
        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(ReportGenerationDefinition.State.WAITING_FOR_REPORT));

        // The next one runs after the poll interval
        emulator.fastForwardTime(ReportGenerationDefinition.POLL_INTERVAL);
        var completed = emulator.runUntilStop(processId);

        assertTrue(completed.state().is(ReportGenerationDefinition.State.COMPLETED));
        assertEquals("https://reports.example.com/job-1.csv",
                completed.variables().get(ReportGenerationDefinition.DOWNLOAD_URL).orElseThrow());
        verify(reportService, times(2)).checkStatus(JOB_ID);
        verify(reportService, never()).cancel(any());
    }

    @Test
    void cancelsJobAfterDeadline() {
        when(reportService.requestReport(any())).thenReturn(JOB_ID);
        when(reportService.checkStatus(JOB_ID)).thenReturn(IN_PROGRESS);
        var processId = startReport();
        emulator.runUntilStop(processId);

        // Checks targeting the await itself do not move the deadline
        emulator.fastForwardTime(ReportGenerationDefinition.FIRST_CHECK_DELAY);
        emulator.runUntilStop(processId);
        for (int i = 0; i < 10; i++) {
            emulator.fastForwardTime(ReportGenerationDefinition.POLL_INTERVAL);
            emulator.runUntilStop(processId);
        }
        emulator.fastForwardTime(ReportGenerationDefinition.REPORT_DEADLINE);
        var timedOut = emulator.runUntilStop(processId);

        assertTrue(timedOut.state().is(ReportGenerationDefinition.State.TIMED_OUT));
        verify(reportService).cancel(JOB_ID);
    }

    @Test
    void retriesFailedCheckInsteadOfStopping() {
        when(reportService.requestReport(any())).thenReturn(JOB_ID);
        when(reportService.checkStatus(JOB_ID))
            .thenThrow(new IllegalStateException("Connection reset"))
            .thenReturn(new ReportStatus(ReportStatus.State.FAILED, null));
        var processId = startReport();
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(ReportGenerationDefinition.FIRST_CHECK_DELAY);
        var retrying = emulator.runUntilStop(processId);
        assertEquals(ProcessStatus.ACTIVE, retrying.status());
        assertEquals(1, retrying.attempt());

        emulator.fastForwardTime(ReportGenerationDefinition.POLL_INTERVAL);
        var failed = emulator.runUntilStop(processId);

        assertTrue(failed.state().is(ReportGenerationDefinition.State.REPORT_FAILED));
    }

    private UUID startReport() {
        return processEngine.newProcess(ReportGenerationDefinition.TYPE)
            .variable(ReportGenerationDefinition.PERIOD, "2026-09")
            .start();
    }
}
