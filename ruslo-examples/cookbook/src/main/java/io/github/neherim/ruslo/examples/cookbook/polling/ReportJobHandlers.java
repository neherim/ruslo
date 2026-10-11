package io.github.neherim.ruslo.examples.cookbook.polling;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

/** Timer handlers of the wait for the report. */
@Component
public class ReportJobHandlers {
    private final ReportService reportService;

    public ReportJobHandlers(ReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Checks the job status. A ready report stores the URL and ends the wait; an unfinished job keeps the process
     * waiting for the next check.
     */
    public Check check(ProcessContext context) {
        var status = reportService.checkStatus(context.getVariable(ReportGenerationDefinition.JOB_ID));
        return switch (status.state()) {
            case IN_PROGRESS -> Check.IN_PROGRESS;
            case READY -> {
                context.setVariable(ReportGenerationDefinition.DOWNLOAD_URL, status.downloadUrl());
                yield Check.READY;
            }
            case FAILED -> Check.FAILED;
        };
    }

    /** Cancels the job the service did not finish by the deadline. */
    public void cancel(ProcessContext context) {
        reportService.cancel(context.getVariable(ReportGenerationDefinition.JOB_ID));
    }

    /** Result of a status check. */
    public enum Check {
        IN_PROGRESS,
        READY,
        FAILED
    }
}
