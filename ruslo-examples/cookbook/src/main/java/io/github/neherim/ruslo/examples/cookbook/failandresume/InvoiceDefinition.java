package io.github.neherim.ruslo.examples.cookbook.failandresume;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Recipe: stop a process on an unrecoverable error, fix the data and continue.
 * <p>
 * Task: issue an invoice for the customer's country. If the country code in the order is wrong, a retry will not help,
 * and no other branch can be chosen automatically: a human must fix the data. The process stops and waits until an
 * operator fixes the country and resumes it.
 * <p>
 * How it works:
 * <ul>
 *   <li>The definition declares no {@code onError}. By default an error in a step is not retried: the process stays
 *       in state {@link State#ISSUING} with status {@code FAILED}, the error text is in {@code lastError}. The
 *       dispatcher does not run such a process.</li>
 *   <li>{@link InvoiceOperations} holds operator actions: find stopped processes, fix a variable
 *       ({@code admin().setProcessVariable}) and resume ({@code resumeFailedProcess}). Resuming repeats the failed
 *       step with the fixed data. The admin UI offers the same actions.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Without {@code onError} a transient failure (a network error, a lock timeout) also stops the process. Retry
 *       such errors in {@code onError} and return {@code ErrorResult.fail()} only for errors that need a human
 *       (recipe {@code retries}).</li>
 *   <li>If an error has an automatic reaction (cancel the order, notify the customer), declare a fallback state
 *       instead of stopping, otherwise stopped processes pile up.</li>
 *   <li>Stopped processes must be noticed: set up an alert on a {@code FAILED} search or on metrics.</li>
 * </ul>
 */
@Component
public class InvoiceDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("INVOICE");
    public static final VariableType<String> COUNTRY = VariableType.of("country", String.class);

    private static final Logger log = LoggerFactory.getLogger(InvoiceDefinition.class);
    private static final Set<String> SUPPORTED_COUNTRIES = Set.of("DE", "FR");

    /** Describes invoicing: on an error the process stops until fixed by hand. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.ISSUING)
            .step(State.ISSUING, InvoiceDefinition::issue, State.ISSUED)
            .terminal(State.ISSUED)
            .build();
    }

    private static void issue(ProcessContext context) {
        var country = context.getVariable(COUNTRY);
        if (!SUPPORTED_COUNTRIES.contains(country)) {
            throw new IllegalArgumentException("Unsupported country: " + country);
        }
        log.info("Invoice for {} issued", context.businessKey().orElseThrow());
    }

    public enum State implements ProcessState {
        ISSUING,
        ISSUED
    }
}
