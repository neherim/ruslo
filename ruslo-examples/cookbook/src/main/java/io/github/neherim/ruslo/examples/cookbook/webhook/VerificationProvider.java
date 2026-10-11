package io.github.neherim.ruslo.examples.cookbook.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Client stub of the external identity verification service. A real application uses an HTTP client here. */
@Component
public class VerificationProvider {
    private static final Logger log = LoggerFactory.getLogger(VerificationProvider.class);

    /**
     * Submits the application for verification. The service sends the result later in a callback with the same
     * {@code applicationId}.
     */
    public void submit(String applicationId) {
        log.info("Verification requested for application {}", applicationId);
    }
}
