package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;

/**
 * Keeps a failed worker attempt's lease and process version across transaction rollback.
 */
public final class WorkerHandlerException extends HandlerException {
    private final ProcessLease lease;
    private final long expectedRowVersion;

    public WorkerHandlerException(RuntimeException cause, ProcessLease lease, long expectedRowVersion) {
        super(cause);
        this.lease = lease;
        this.expectedRowVersion = expectedRowVersion;
    }

    public ProcessLease getLease() {
        return lease;
    }

    public long getExpectedRowVersion() {
        return expectedRowVersion;
    }
}
