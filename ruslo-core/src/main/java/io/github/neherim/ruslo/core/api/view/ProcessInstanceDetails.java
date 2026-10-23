package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Process instance with variables, last error and state history. The message queue is not exposed.
 *
 * @param nextRunAt when the engine next takes the process: a step, a retry, a queued message or a timer; empty if it
 *                  waits only for messages, is {@code FAILED} or completed
 * @param lastError last failure as {@code SimpleClassName: message} (class name only without a message),
 *                  truncated to 1024 characters
 */
public record ProcessInstanceDetails(
        UUID id,
        ProcessTypeId type,
        ProcessStateId state,
        ProcessStatus status,
        Instant createdAt,
        Optional<Instant> nextRunAt,
        Optional<String> businessKey,
        String correlationId,
        int attempt,
        Optional<String> lastError,
        List<StateHistoryEntry> stateHistory,
        ProcessVariablesView variables
) {

    /** State history entry: the state and when it was entered. */
    public record StateHistoryEntry(ProcessStateId state, Instant changedAt) {
    }
}
