package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Process instance in search results. */
public record ProcessInstanceSummary(
    UUID id, ProcessTypeId type, ProcessStateId state, ProcessStatus status,
    Instant createdAt, Optional<Instant> scheduledAt, Optional<String> businessKey, int attempt) {
}
