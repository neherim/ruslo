package io.github.neherim.ruslo.examples.cookbook.polling;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Recipe: poll the status of a long job in an external system.
 * <p>
 * Task: order a report from an external service. The service builds it for minutes and cannot send a callback, so
 * the status has to be polled. If the report is not ready within an hour, the job must be cancelled.
 * <p>
 * How it works:
 * <ul>
 *   <li>Step {@link State#REQUESTING} submits the job and stores its id.</li>
 *   <li>Await {@link State#WAITING_FOR_REPORT} checks the status with {@code every}. The handler returns the
 *       service's {@link ReportStatus} as the outcome: while the job is in progress, the target is the await itself
 *       ("keep waiting"); a ready report leads to {@link State#COMPLETED}, a service error to
 *       {@link State#REPORT_FAILED}.</li>
 *   <li>{@code initialDelay} postpones the first check: the report is never ready right after the order.
 *       Subsequent checks run every {@link #POLL_INTERVAL}.</li>
 *   <li>{@code timeout} is the overall deadline: it counts from entering the await, and checks targeting the await
 *       itself do not reset it. The deadline handler cancels the job at the service.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Do not poll with a "check step → await → step" loop: each entry into the await restarts {@code timeout},
 *       and the deadline never comes. Recipe {@code approval} uses this loop on purpose, to give a new approver a
 *       new deadline.</li>
 *   <li>The {@code every} handler runs on the transactional {@code DEFAULT} worker and holds a DB connection while
 *       waiting for the service. The status request must be short, with an HTTP client timeout.</li>
 *   <li>If the service can send a callback, declare a {@code message} in the same await (recipe {@code webhook})
 *       and keep {@code every} with a long interval as insurance against a lost callback.</li>
 * </ul>
 * <p>
 * How late and missed checks are ordered against the deadline is described in {@code docs/execution.md}.
 */
@Component
public class ReportGenerationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("REPORT_GENERATION");
    public static final Duration FIRST_CHECK_DELAY = Duration.ofSeconds(30);
    public static final Duration POLL_INTERVAL = Duration.ofMinutes(2);
    public static final Duration REPORT_DEADLINE = Duration.ofHours(1);
    public static final VariableType<String> PERIOD = VariableType.of("period", String.class);
    public static final VariableType<String> JOB_ID = VariableType.of("jobId", String.class);

    private final ReportService reportService;

    public ReportGenerationDefinition(ReportService reportService) {
        this.reportService = reportService;
    }

    /** Describes a report order: submit the job and poll its status until ready or the deadline. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.REQUESTING)
            .step(State.REQUESTING, this::requestReport, State.WAITING_FOR_REPORT)
            .await(State.WAITING_FOR_REPORT, await -> await
                .every(POLL_INTERVAL, this::checkStatus,
                        routes -> routes
                            .on(ReportStatus.IN_PROGRESS, State.WAITING_FOR_REPORT)
                            .on(ReportStatus.READY, State.COMPLETED)
                            .on(ReportStatus.FAILED, State.REPORT_FAILED),
                        options -> options.initialDelay(FIRST_CHECK_DELAY))
                .timeout(REPORT_DEADLINE, this::cancel, State.TIMED_OUT))
            .terminal(State.COMPLETED)
            .terminal(State.REPORT_FAILED)
            .terminal(State.TIMED_OUT)
            .build();
    }

    private void requestReport(ProcessContext context) {
        context.setVariable(JOB_ID, reportService.requestReport(context.getVariable(PERIOD)));
    }

    private ReportStatus checkStatus(ProcessContext context) {
        return reportService.checkStatus(context.getVariable(JOB_ID));
    }

    private void cancel(ProcessContext context) {
        reportService.cancel(context.getVariable(JOB_ID));
    }

    public enum State implements ProcessState {
        REQUESTING,
        WAITING_FOR_REPORT,
        COMPLETED,
        REPORT_FAILED,
        TIMED_OUT
    }
}
