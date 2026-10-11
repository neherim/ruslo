package io.github.neherim.ruslo.examples.cookbook.polling;

/**
 * Status of a report job at the external service.
 *
 * @param downloadUrl URL of the ready report; present only in state {@link State#READY}
 */
public record ReportStatus(State state, String downloadUrl) {

    /** Job state at the external service. */
    public enum State {
        IN_PROGRESS,
        READY,
        FAILED
    }
}
