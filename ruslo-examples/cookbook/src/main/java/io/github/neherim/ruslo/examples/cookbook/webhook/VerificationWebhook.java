package io.github.neherim.ruslo.examples.cookbook.webhook;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Accepts callbacks from the external service. In an application the webhook REST controller calls it: a normal
 * return maps to {@code 200}, an exception to an error response, after which the service retries the delivery. A
 * {@code sendMessage} rejection is a {@link MessageNotExpectedException}. The engine throws it after leaving its
 * transaction, so a caught rejection does not roll back the application transaction.
 */
@Component
public class VerificationWebhook {
    private static final Logger log = LoggerFactory.getLogger(VerificationWebhook.class);

    private final ProcessEngine processEngine;

    public VerificationWebhook(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    /**
     * Finds the process by application id and delivers the callback to it. A callback for an unknown application and
     * a repeated callback for a completed process are acknowledged without action: redelivery would not fix them.
     */
    public void onCallback(VerificationCallback callback) {
        var process = processEngine.processes()
                .byType(IdentityVerificationDefinition.TYPE)
                .byBusinessKey(callback.applicationId())
                .single();
        if (process.isEmpty()) {
            log.warn("Verification callback {} for unknown application {} ignored",
                    callback.eventId(), callback.applicationId());
            return;
        }
        try {
            processEngine.sendMessage(process.get().id(), IdentityVerificationDefinition.CALLBACK, callback);
        } catch (MessageNotExpectedException ex) {
            log.info("Verification callback {} for application {} in state {} ignored",
                    callback.eventId(), callback.applicationId(), ex.getCurrentState());
        }
    }
}
