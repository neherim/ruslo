package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.util.UUID;

/**
 * Result of resuming a {@code FAILED} process, see
 * {@link ProcessEngine#resumeFailedProcess(UUID, ProcessState)}.
 * <p>
 * The rejection depends on the current process state, so it is returned instead of thrown: an exception thrown
 * inside the engine transaction would mark it rollback-only even if caught. Use {@link #orThrow()} to turn it
 * into a {@link ProcessStatusException} where the caller needs one.
 */
public sealed interface ResumeResult {

    /** The process is resumed; the failed step or event runs again, or the target state is entered. */
    record Resumed() implements ResumeResult {}

    /** The process is not {@code FAILED}; nothing was resumed. */
    record NotFailed(UUID processId, ProcessStatus status) implements ResumeResult {}

    /**
     * Returns {@code this} if the process is resumed, throws a {@link ProcessStatusException} for a rejection.
     */
    default Resumed orThrow() {
        return switch (this) {
            case Resumed r -> r;
            case NotFailed n -> throw new ProcessStatusException(n.processId(), n.status(),
                    "only a FAILED process can be resumed");
        };
    }
}
