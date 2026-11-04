package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Process instance in search results. Type and state are the declared objects, see
 * {@link io.github.neherim.ruslo.core.api.definition.ProcessState}.
 *
 * @param nextRunAt when the engine next takes the process: a step, a retry, a queued message or a timer; empty if it
 *                  waits only for messages, is {@code FAILED} or completed
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
