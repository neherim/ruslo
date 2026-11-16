package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Process instance in search results.
 */
public record ProcessInstanceSummary(
        UUID id,
        ProcessType type,
        ProcessState state,
        ProcessStatus status,
        Instant createdAt,
        Optional<Instant> nextRunAt,
        Optional<String> businessKey,
        int attempt
) {
}
