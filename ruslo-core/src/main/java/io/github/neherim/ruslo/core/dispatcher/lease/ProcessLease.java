package io.github.neherim.ruslo.core.dispatcher.lease;

import java.util.Objects;
import java.util.UUID;

/**
 * Ownership of a process by one engine registration.
 *
 * @param ownerId registration id of the engine instance, see {@link ApplicationInstanceLifecycle}
 */
public record ProcessLease(UUID processId, UUID ownerId) {
    public ProcessLease {
        Objects.requireNonNull(processId, "Process id must not be null");
        Objects.requireNonNull(ownerId, "Lease owner id must not be null");
    }
}
