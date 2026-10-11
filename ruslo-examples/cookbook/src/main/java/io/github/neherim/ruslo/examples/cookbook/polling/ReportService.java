package io.github.neherim.ruslo.examples.cookbook.polling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * External report service stub. A real application uses an HTTP client here. The report is ready by the third
 * status check.
 */
@Component
public class ReportService {
    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final Map<String, Integer> checks = new ConcurrentHashMap<>();

    /**
     * Submits a report job.
     *
     * @return job id at the external service
     */
    public String requestReport(String period) {
        var jobId = "job-" + UUID.randomUUID();
        log.info("Report for {} requested, job {}", period, jobId);
        return jobId;
    }

    /** Returns the current job status. */
    public ReportStatus checkStatus(String jobId) {
        if (checks.merge(jobId, 1, Integer::sum) < 3) {
            return new ReportStatus(ReportStatus.State.IN_PROGRESS, null);
        }
        return new ReportStatus(ReportStatus.State.READY, "https://reports.example.com/" + jobId + ".csv");
    }

    /** Cancels the job so the service does not spend resources on it. */
    public void cancel(String jobId) {
        log.info("Report job {} cancelled", jobId);
    }
}
