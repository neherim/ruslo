package io.github.neherim.ruslo.examples.cookbook.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stub for emails to the applicant. */
@Component
public class ApplicantNotifier {
    private static final Logger log = LoggerFactory.getLogger(ApplicantNotifier.class);

    /** Asks the applicant to send more documents. */
    public void requestDocuments(String email) {
        log.info("Documents requested from {}", email);
    }
}
