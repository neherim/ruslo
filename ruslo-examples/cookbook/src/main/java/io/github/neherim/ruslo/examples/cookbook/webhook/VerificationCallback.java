package io.github.neherim.ruslo.examples.cookbook.webhook;

/**
 * Callback from the external identity verification service.
 *
 * @param applicationId application id passed to the service on submit; the process business key
 * @param eventId       event id at the service; a repeated delivery comes with the same id
 */
public record VerificationCallback(String applicationId, String eventId, Status status) {

    /** Verification status at the external service. */
    public enum Status {
        DOCUMENTS_REQUIRED,
        APPROVED,
        DECLINED
    }
}
