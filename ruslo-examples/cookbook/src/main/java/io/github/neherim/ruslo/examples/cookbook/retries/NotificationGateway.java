package io.github.neherim.ruslo.examples.cookbook.retries;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** External notification gateway stub. A real application uses an HTTP or message broker client here. */
@Component
public class NotificationGateway {
    private static final Logger log = LoggerFactory.getLogger(NotificationGateway.class);

    /**
     * Sends a notification.
     *
     * @throws InvalidRecipientException if the gateway rejected the recipient
     */
    public void send(String recipient, String text) {
        if (!recipient.contains("@")) {
            throw new InvalidRecipientException(recipient);
        }
        log.info("Notification sent to {}: {}", recipient, text);
    }
}
