package io.github.neherim.ruslo.examples.cookbook.retries;

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

import java.time.Duration;

/**
 * Recipe: retries while an external service is temporarily unavailable.
 * <p>
 * Task: send a notification through an external gateway that sometimes answers with an error. A retry after a pause
 * cures a transient failure. If the gateway stays down for long or rejects the notification itself, the process goes
 * to fallback state {@link State#DELIVERY_FAILED}, where the application decides what to do next.
 * <p>
 * How it works: {@code onError} is called on an exception in a step and returns a decision.
 * <ul>
 *   <li>{@link InvalidRecipientException} cannot be fixed by a retry: {@link ErrorResult#fallback()} at once.</li>
 *   <li>Other errors are considered transient: the built-in {@link ProcessErrorHandler#exponential} retries the step
 *       after 1, 2 and 4 seconds, and after {@link #MAX_RETRIES} retries also returns {@code fallback()}.</li>
 *   <li>{@code fallback()} moves the process to the state passed as the second argument of {@code onError}.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>A retry runs the whole step again. If the step did something in an external system before the error, that
 *       action repeats: the external call must be idempotent.</li>
 *   <li>The attempt counter ({@link ProcessError#attempt()}) belongs to the current state and resets on the
 *       transition to the next one.</li>
 *   <li>Without a fallback state {@code fallback()} stops the process in status {@code FAILED} until a manual
 *       {@code resumeFailedProcess}. Use this when a human must look into the error (recipe
 *       {@code failandresume}).</li>
 * </ul>
 */
@Component
public class NotificationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("NOTIFICATION");
    public static final VariableType<String> RECIPIENT = VariableType.of("recipient", String.class);
    public static final VariableType<String> TEXT = VariableType.of("text", String.class);

    static final int MAX_RETRIES = 3;
    private static final ProcessErrorHandler BACKOFF =
            ProcessErrorHandler.exponential(MAX_RETRIES, Duration.ofSeconds(1), Duration.ofMinutes(1));

    private final SendNotificationStep sendNotificationStep;

    public NotificationDefinition(SendNotificationStep sendNotificationStep) {
        this.sendNotificationStep = sendNotificationStep;
    }

    /** Describes sending a notification with retries and a fallback state. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .onError(this::onError, State.DELIVERY_FAILED)
                .initialState(State.SENDING)
                .step(State.SENDING, sendNotificationStep, State.SENT)
                .terminal(State.SENT)
                .terminal(State.DELIVERY_FAILED)
                .build();
    }

    /** Does not retry errors a retry cannot fix; retries the rest with a growing delay. */
    private ErrorResult onError(ProcessContext context, ProcessError error) {
        if (error.cause() instanceof InvalidRecipientException) {
            return ErrorResult.fallback();
        }
        return BACKOFF.handle(context, error);
    }

    public enum State implements ProcessState {
        SENDING,
        SENT,
        DELIVERY_FAILED
    }
}
