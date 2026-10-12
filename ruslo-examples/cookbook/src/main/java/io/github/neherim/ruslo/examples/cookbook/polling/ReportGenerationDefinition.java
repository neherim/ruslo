package io.github.neherim.ruslo.examples.cookbook.polling;

import io.github.neherim.ruslo.core.api.definition.*;
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
 *   <li>Step {@link RequestReportStep} submits the job and stores its id.</li>
 *   <li>Await {@link State#WAITING_FOR_REPORT} checks the status with {@code every}. Handler
 *       {@link ReportJobHandlers#check} branches: while the job is not ready, the target is the await itself
 *       ("keep waiting"); a ready report leads to {@link State#COMPLETED}, a service error to
 *       {@link State#REPORT_FAILED}.</li>
 *   <li>{@code initialDelay} postpones the first check: the report is never ready right after the order.
 *       Subsequent checks run every {@link #POLL_INTERVAL}.</li>
 *   <li>{@code timeout} is the overall deadline: it counts from entering the await, and checks targeting the await
 *       itself do not reset it. The deadline handler cancels the job at the service.</li>
 *   <li>{@code onError} retries a check on a network failure instead of stopping the process on the first
 *       error. A successful check resets the attempt counter.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Do not poll with a "check step → await → step" loop: each entry into the await restarts {@code timeout},
 *       and the deadline never comes.</li>
 *   <li>The {@code every} handler runs on the transactional {@code DEFAULT} worker and holds a DB connection while
 *       waiting for the service. The status request must be short, with an HTTP client timeout.</li>
 *   <li>Checks missed while the application was down do not run in a burst: one runs after startup, the next one
 *       after the interval.</li>
 *   <li>A check scheduled before the deadline but run after it is handled before {@code timeout} and sees the
 *       current service status. Event order is described in {@code docs/execution.md}.</li>
 *   <li>If the service can send a callback, declare a {@code message} in the same await (recipe {@code webhook})
 *       and keep {@code every} with a long interval as insurance against a lost callback.</li>
 * </ul>
 */
@Component
public class ReportGenerationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("REPORT_GENERATION");
    public static final Duration FIRST_CHECK_DELAY = Duration.ofSeconds(30);
    public static final Duration POLL_INTERVAL = Duration.ofMinutes(2);
    public static final Duration REPORT_DEADLINE = Duration.ofHours(1);
    public static final VariableType<String> PERIOD = VariableType.of("period", String.class);
    public static final VariableType<String> JOB_ID = VariableType.of("jobId", String.class);
    public static final VariableType<String> DOWNLOAD_URL = VariableType.of("downloadUrl", String.class);

    private final RequestReportStep requestReportStep;
    private final ReportJobHandlers reportJobHandlers;

    public ReportGenerationDefinition(RequestReportStep requestReportStep, ReportJobHandlers reportJobHandlers) {
        this.requestReportStep = requestReportStep;
        this.reportJobHandlers = reportJobHandlers;
    }

    /**
     * Describes a report order: submit the job and poll its status until ready or the deadline.
     */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .onError(ProcessErrorHandler.fixed(3, Duration.ofSeconds(30)))
            .initialState(State.REQUESTING)
            .step(State.REQUESTING, requestReportStep, State.WAITING_FOR_REPORT)
            .await(State.WAITING_FOR_REPORT, await -> await
                .every(POLL_INTERVAL, reportJobHandlers::check,
                        routes -> routes
                            .on(ReportJobHandlers.Check.IN_PROGRESS, State.WAITING_FOR_REPORT)
                            .on(ReportJobHandlers.Check.READY, State.COMPLETED)
                            .on(ReportJobHandlers.Check.FAILED, State.REPORT_FAILED),
                        options -> options.initialDelay(FIRST_CHECK_DELAY))
                .timeout(REPORT_DEADLINE, reportJobHandlers::cancel, State.TIMED_OUT))
            .terminal(State.COMPLETED)
            .terminal(State.REPORT_FAILED)
            .terminal(State.TIMED_OUT)
            .build();
    }

    public enum State implements ProcessState {
        REQUESTING,
        WAITING_FOR_REPORT,
        COMPLETED,
        REPORT_FAILED,
        TIMED_OUT
    }
}
