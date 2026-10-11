package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;

import java.util.Objects;

/**
 * Fencing snapshot of a claimed process.
 *
 * @param expectedRowVersion row version read with the snapshot
 */
public record ClaimedProcess(ProcessInstance process, ProcessLease lease, long expectedRowVersion) {
    /** Checks that the process, lease and row version match. */
    public ClaimedProcess {
        Objects.requireNonNull(process, "Process must not be null");
        Objects.requireNonNull(lease, "Lease must not be null");
        if (!process.getId().equals(lease.processId())) {
            throw new IllegalArgumentException("Lease process id does not match process id");
        }
        if (expectedRowVersion < 0) {
            throw new IllegalArgumentException("Expected row version must not be negative");
        }
    }
}
