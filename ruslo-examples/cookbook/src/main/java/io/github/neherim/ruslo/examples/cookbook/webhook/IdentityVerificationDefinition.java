package io.github.neherim.ruslo.examples.cookbook.webhook;

import io.github.neherim.ruslo.core.api.definition.MessageType;
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

/**
 * Recipe: accept a callback from an external service and ignore its repeats.
 * <p>
 * Task: verify the customer's identity through an external service. The service does not answer at once but sends a
 * callback (webhook) with the result, sometimes several times: first a request for more documents, then the
 * decision. If our answer to a callback is lost, the service repeats it with the same {@code eventId}. A repeat must
 * not ask the customer for documents again.
 * <p>
 * How it works:
 * <ul>
 *   <li>{@link VerificationWebhook} finds the process by the application id, which is the process business key, and
 *       delivers the callback with {@code sendMessage}.</li>
 *   <li>{@code sendMessage} is not idempotent: a repeat is queued and handled like a new callback. So
 *       {@link VerificationCallbackHandler} keeps the ids of handled callbacks in variable {@link #HANDLED_EVENTS}.
 *       On a known id it does nothing and keeps waiting (the target is the await itself). Otherwise it adds the id,
 *       and the status decides: a request for documents keeps waiting, a decision leads to {@link State#VERIFIED} or
 *       {@link State#REJECTED}.</li>
 *   <li>The variable and the message removal from the queue commit in one transaction, so a callback is either
 *       handled and remembered, or neither.</li>
 *   <li>After a decision the process has completed and keeps no await: {@code sendMessage} throws
 *       {@code MessageNotExpectedException}, and the webhook acknowledges the repeat without doing anything.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Variables are stored as one JSON and read on every event. A list of ids suits a few callbacks per process.
 *       For a long stream of events keep handled ids in an application table with a unique key, written in the
 *       handler's transaction.</li>
 *   <li>A callback may arrive while step {@link State#SUBMITTING} is still running. On the transactional
 *       {@code DEFAULT} worker {@code sendMessage} waits for the step and finds the await
 *       ({@code docs/en/execution.md}). On a non-transactional worker it throws {@code MessageNotExpectedException},
 *       and the webhook must answer with an error so the service retries.</li>
 *   <li>The business key is not unique: {@code single()} throws {@code TooManyRowsException} if two processes of
 *       the type share it.</li>
 * </ul>
 */
@Component
public class IdentityVerificationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("IDENTITY_VERIFICATION");
    public static final VariableType<String> APPLICANT_EMAIL = VariableType.of("applicantEmail", String.class);
    public static final VariableType<HandledEvents> HANDLED_EVENTS = VariableType.of("handledEvents",
            HandledEvents.class);
    public static final MessageType<VerificationCallback> CALLBACK = MessageType.of("verificationCallback",
            VerificationCallback.class);

    private static final Logger log = LoggerFactory.getLogger(IdentityVerificationDefinition.class);

    private final VerificationCallbackHandler callbackHandler;

    public IdentityVerificationDefinition(VerificationCallbackHandler callbackHandler) {
        this.callbackHandler = callbackHandler;
    }

    /** Describes identity verification: submit the application and wait for a callback with the result. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.SUBMITTING)
            .step(State.SUBMITTING, IdentityVerificationDefinition::submit, State.WAITING_FOR_RESULT)
            .await(State.WAITING_FOR_RESULT, await -> await
                .message(CALLBACK, callbackHandler, routes -> routes
                    .on(VerificationCallbackHandler.Result.WAIT, State.WAITING_FOR_RESULT)
                    .on(VerificationCallbackHandler.Result.APPROVED, State.VERIFIED)
                    .on(VerificationCallbackHandler.Result.DECLINED, State.REJECTED)))
            .terminal(State.VERIFIED)
            .terminal(State.REJECTED)
            .build();
    }

    /** Submits the application; the service returns the business key in every callback. */
    private static void submit(ProcessContext context) {
        log.info("Verification requested for application {}", context.businessKey().orElseThrow());
    }

    public enum State implements ProcessState {
        SUBMITTING,
        WAITING_FOR_RESULT,
        VERIFIED,
        REJECTED
    }
}
