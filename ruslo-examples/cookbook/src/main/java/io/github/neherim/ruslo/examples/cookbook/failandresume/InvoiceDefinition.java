package io.github.neherim.ruslo.examples.cookbook.failandresume;

import io.github.neherim.ruslo.core.api.definition.ErrorResult;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessError;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Recipe: stop a process on an unrecoverable error, fix the data and continue.
 * <p>
 * Task: issue an invoice, calculating tax by the customer's country. If the country code in the order is wrong, a
 * retry will not help, and no other branch can be chosen automatically: a human must fix the data. The process stops
 * and waits until an operator fixes the country and resumes it.
 * <p>
 * How it works:
 * <ul>
 *   <li>{@code onError} returns {@link ErrorResult#fail()} for {@link UnsupportedCountryException}: the process stays
 *       in state {@link State#ISSUING} with status {@code FAILED}, the error text is in {@code lastError}. The
 *       dispatcher does not run such a process.</li>
 *   <li>Other errors are considered transient and are retried; after {@link #MAX_RETRIES} retries
 *       {@code fallback()} without a fallback state also leaves the process in {@code FAILED}.</li>
 *   <li>{@link InvoiceOperations} holds operator actions: find stopped and retrying processes, fix a variable
 *       ({@code admin().setProcessVariable}) and resume ({@code resumeFailedProcess}). Resuming repeats the failed
 *       step with the fixed data. The admin UI offers the same actions.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>{@code fail()} is for errors that need a human. If an error has an automatic reaction (cancel the order,
 *       notify the customer), declare a fallback state and return {@code fallback()} (recipe {@code retries}),
 *       otherwise stopped processes pile up.</li>
 *   <li>Stopped processes must be noticed: set up an alert on a {@code FAILED} search or on metrics.</li>
 *   <li>{@code resumeFailedProcess} applies only to {@code FAILED}; for a process that is still retrying the step
 *       ({@code byAttempted()}), fixing the data is enough: the next retry sees it.</li>
 *   <li>The attempt counter starts over after {@code resumeFailedProcess}.</li>
 * </ul>
 */
@Component
public class InvoiceDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("INVOICE");
    public static final VariableType<BigDecimal> AMOUNT = VariableType.of("amount", BigDecimal.class);
    public static final VariableType<String> COUNTRY = VariableType.of("country", String.class);
    public static final VariableType<BigDecimal> TAX = VariableType.of("tax", BigDecimal.class);

    static final int MAX_RETRIES = 3;
    private static final ProcessErrorHandler BACKOFF =
            ProcessErrorHandler.exponential(MAX_RETRIES, Duration.ofSeconds(10), Duration.ofMinutes(5));

    private final IssueInvoiceStep issueInvoiceStep;

    public InvoiceDefinition(IssueInvoiceStep issueInvoiceStep) {
        this.issueInvoiceStep = issueInvoiceStep;
    }

    /** Describes invoicing: on bad data the process stops until fixed by hand. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .onError(this::onError)
                .initialState(State.ISSUING)
                .step(State.ISSUING, issueInvoiceStep, State.ISSUED)
                .terminal(State.ISSUED)
                .build();
    }

    /** Stops the process on bad data; retries transient errors. */
    private ErrorResult onError(ProcessContext context, ProcessError error) {
        if (error.cause() instanceof UnsupportedCountryException) {
            return ErrorResult.fail();
        }
        return BACKOFF.handle(context, error);
    }

    public enum State implements ProcessState {
        ISSUING,
        ISSUED
    }
}
