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

    /**
     * Keeps waiting on a repeated callback without doing anything. Otherwise remembers its {@code eventId}, forwards
     * a request for more documents to the customer and keeps waiting; a final status ends the wait.
     */
    @Override
    public Result handle(ProcessContext context, IncomingMessage<VerificationCallback> message) {
        var callback = message.payload();
        var handled = Objects.requireNonNullElse(context.getVariable(IdentityVerificationDefinition.HANDLED_EVENTS),
                HandledEvents.NONE);
        if (handled.contains(callback.eventId())) {
            log.info("Repeated verification callback {} ignored", callback.eventId());
            return Result.WAIT;
        }
        context.setVariable(IdentityVerificationDefinition.HANDLED_EVENTS, handled.with(callback.eventId()));
        return switch (callback.status()) {
            case DOCUMENTS_REQUIRED -> {
                log.info("Documents requested from {}",
                        context.getVariable(IdentityVerificationDefinition.APPLICANT_EMAIL));
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
