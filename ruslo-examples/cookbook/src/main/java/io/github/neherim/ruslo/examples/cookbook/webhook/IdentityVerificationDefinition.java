package io.github.neherim.ruslo.examples.cookbook.webhook;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Recipe: accept a callback from an external service.
 * <p>
 * Task: verify the customer's identity through an external service. The service does not answer at once but sends a
 * callback (webhook) with the result, sometimes several times: first a request for more documents, then the
 * decision. If our answer to a callback is lost, the service repeats it with the same {@code eventId}.
 * <p>
 * How it works:
 * <ul>
 *   <li>The process starts with the application id as the business key. Step {@link SubmitVerificationStep} passes
 *       this id to the service, and the service returns it in every callback. That is how
 *       {@link VerificationWebhook} finds the process:
 *       {@code processes().byType(TYPE).byBusinessKey(applicationId)}.</li>
 *   <li>The callback is delivered to the process with {@code sendMessage}. Handler
 *       {@link VerificationCallbackHandler} branches on the status: an intermediate status keeps the process waiting
 *       (the target is the await itself), a final one leads to {@link State#VERIFIED} or {@link State#REJECTED}.</li>
 *   <li>Duplicate callbacks are dropped in two places. While the process waits, the handler remembers handled
 *       {@code eventId}s in variable {@link #PROCESSED_EVENTS}: the check and the write run in one transaction with
 *       the handling, so the customer does not get the email twice. Once the process has completed,
 *       {@code sendMessage} throws {@code MessageNotExpectedException}, and the webhook acknowledges the callback
 *       without doing anything.</li>
 *   <li>{@code timeout} closes the application if the service never answers.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>The service may send a callback before the process enters the await: right during the submit step. The step
 *       runs on the transactional {@code DEFAULT} worker and holds the process row lock, so {@code sendMessage}
 *       waits for the step to end and finds the await. On a non-transactional worker such a callback would get
 *       {@code MessageNotExpectedException} from an unfinished process; the webhook would then have to answer with
 *       an error so the service retries the delivery.</li>
 *   <li>{@code sendMessage} is not idempotent: two calls with the same payload queue two messages. Without the
 *       {@code eventId} check in the handler a duplicate would repeat the side effect.</li>
 *   <li>The business key is not unique: the engine does not prevent two processes with the same key. The type
 *       filter narrows the search, and {@code single()} throws {@code TooManyRowsException} if there are still
 *       two.</li>
 * </ul>
 */
@Component
public class IdentityVerificationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("IDENTITY_VERIFICATION");
    public static final Duration RESPONSE_DEADLINE = Duration.ofDays(3);
    public static final VariableType<String> APPLICANT_EMAIL = VariableType.of("applicantEmail", String.class);
    public static final VariableType<ProcessedEvents> PROCESSED_EVENTS =
            VariableType.of("processedEvents", ProcessedEvents.class);
    public static final MessageType<VerificationCallback> CALLBACK =
            MessageType.of("verificationCallback", VerificationCallback.class);

    private final SubmitVerificationStep submitVerificationStep;
    private final VerificationCallbackHandler callbackHandler;

    public IdentityVerificationDefinition(SubmitVerificationStep submitVerificationStep,
                                          VerificationCallbackHandler callbackHandler) {
        this.submitVerificationStep = submitVerificationStep;
        this.callbackHandler = callbackHandler;
    }

    /** Describes identity verification: submit the application and wait for a callback with the result. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .initialState(State.SUBMITTING)
                .step(State.SUBMITTING, submitVerificationStep, State.WAITING_FOR_RESULT)
                .await(State.WAITING_FOR_RESULT, await -> await
                        .message(CALLBACK, callbackHandler, routes -> routes
                                .on(VerificationCallbackHandler.Result.WAIT, State.WAITING_FOR_RESULT)
                                .on(VerificationCallbackHandler.Result.APPROVED, State.VERIFIED)
                                .on(VerificationCallbackHandler.Result.DECLINED, State.REJECTED))
                        .timeout(RESPONSE_DEADLINE, State.NO_RESPONSE))
                .terminal(State.VERIFIED)
                .terminal(State.REJECTED)
                .terminal(State.NO_RESPONSE)
                .build();
    }

    public enum State implements ProcessState {
        SUBMITTING,
        WAITING_FOR_RESULT,
        VERIFIED,
        REJECTED,
        NO_RESPONSE
    }
}
