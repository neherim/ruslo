package io.github.neherim.ruslo.examples.cookbook.polling;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class RequestReportStep implements ProcessStep {
    private final ReportService reportService;

    public RequestReportStep(ReportService reportService) {
        this.reportService = reportService;
    }

    /** Submits the job to the external service and stores its id for status checks. */
    @Override
    public void execute(ProcessContext context) {
        var jobId = reportService.requestReport(context.getVariable(ReportGenerationDefinition.PERIOD));
        context.setVariable(ReportGenerationDefinition.JOB_ID, jobId);
    }
}
