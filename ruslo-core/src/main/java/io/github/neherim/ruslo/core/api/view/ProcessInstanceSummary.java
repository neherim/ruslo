package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Process instance in search results.
 *
 * @param nextRunAt when the engine next takes the process: a step, a retry, a queued message or a timer; empty if it
 *                  waits only for messages, is {@code FAILED} or completed
 */
public record ProcessInstanceSummary(
        UUID id,
        ProcessTypeId type,
        ProcessStateId state,
        ProcessStatus status,
        Instant createdAt,
        Optional<Instant> nextRunAt,
        Optional<String> businessKey,
        int attempt
) {
}
