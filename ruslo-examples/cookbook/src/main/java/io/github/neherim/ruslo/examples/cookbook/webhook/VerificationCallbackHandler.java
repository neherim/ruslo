package io.github.neherim.ruslo.examples.cookbook.webhook;

import io.github.neherim.ruslo.core.api.definition.BranchingMessageHandler;
import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public class VerificationCallbackHandler
        implements BranchingMessageHandler<VerificationCallback, VerificationCallbackHandler.Result> {
    private static final Logger log = LoggerFactory.getLogger(VerificationCallbackHandler.class);

    private final ApplicantNotifier applicantNotifier;

    public VerificationCallbackHandler(ApplicantNotifier applicantNotifier) {
        this.applicantNotifier = applicantNotifier;
    }

    /**
     * Handles a callback once per {@code eventId}: a repeated delivery keeps the process waiting. A request for more
     * documents is forwarded to the customer; a final status ends the wait.
     */
    @Override
    public Result handle(ProcessContext context, IncomingMessage<VerificationCallback> message) {
        var callback = message.payload();
        var processed = Objects.requireNonNullElse(
                context.getVariable(IdentityVerificationDefinition.PROCESSED_EVENTS), ProcessedEvents.NONE);
        if (processed.contains(callback.eventId())) {
            log.info("Duplicate verification callback {} ignored", callback.eventId());
            return Result.WAIT;
        }
        context.setVariable(IdentityVerificationDefinition.PROCESSED_EVENTS, processed.with(callback.eventId()));
        return switch (callback.status()) {
            case DOCUMENTS_REQUIRED -> {
                applicantNotifier.requestDocuments(context.getVariable(IdentityVerificationDefinition.APPLICANT_EMAIL));
                yield Result.WAIT;
            }
            case APPROVED -> Result.APPROVED;
            case DECLINED -> Result.DECLINED;
        };
    }

    /** Result of handling a callback. */
    public enum Result {
        WAIT,
        APPROVED,
        DECLINED
    }
}
