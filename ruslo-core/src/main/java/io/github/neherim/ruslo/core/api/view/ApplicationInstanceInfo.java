package io.github.neherim.ruslo.core.api.view;

import java.time.Duration;
import java.util.UUID;

/**
 * Registered engine instance with a running dispatcher.
 *
 * @param id         registration id, also the lease owner
 * @param expiresIn  time until the registration expires without a heartbeat, by database time; negative if it has
 *                   expired but recovery has not removed it yet
 * @param leaseCount number of processes claimed by the instance
 * @param current    {@code true} for this engine's registration
 */
public record ApplicationInstanceInfo(UUID id, Duration expiresIn, long leaseCount, boolean current) {
}
