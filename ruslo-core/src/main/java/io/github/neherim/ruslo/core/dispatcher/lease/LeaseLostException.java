package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.api.exception.ProcessEngineException;

import java.util.UUID;

/**
 * A normal concurrent outcome: the task owner no longer holds the lease, or the process was changed concurrently
 * after it was read.
 */
public final class LeaseLostException extends ProcessEngineException {
    public LeaseLostException(UUID processId) {
        super("Lease ownership was lost or process was changed concurrently: " + processId);
    }
}
