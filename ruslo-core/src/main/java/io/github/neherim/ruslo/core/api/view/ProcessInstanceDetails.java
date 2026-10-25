package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Process instance with variables, timers, last error and state history. The message queue is not exposed.
 *
 * @param nextRunAt when the engine next takes the process: a step, a retry, a queued message or a timer; empty if it
 *                  waits only for messages, is {@code FAILED} or completed
 * @param lastError last failure as {@code SimpleClassName: message} (class name only without a message),
 *                  truncated to 1024 characters
 * @param timers    process timer name to moment, as set by
 *                  {@link io.github.neherim.ruslo.core.api.definition.ProcessContext#setTimer}; includes timers no
 *                  {@code at} of the current definition uses. The moments of {@code every} and {@code after} are not
 *                  included
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
        ProcessVariablesView variables,
        Map<String, Instant> timers
) {
    private static final int HISTORY_LIMIT = 10;

    /**
     * Returns a multi-line summary for test failures and logs: type, id, status, state, attempt, last error, the last
     * states of the history, variables as JSON, timers and the next run.
     */
    @Override
    public String toString() {
        var text = new StringBuilder()
            .append(type.name())
            .append(' ')
            .append(id)
            .append(' ')
            .append(status)
            .append(" in ")
            .append(state.name())
            .append(", attempt ")
            .append(attempt);
        businessKey.ifPresent(key -> text.append("\n  businessKey: ").append(key));
        lastError.ifPresent(error -> text.append("\n  lastError:   ").append(error));
        if (!stateHistory.isEmpty()) {
            text.append("\n  history:     ").append(history());
        }
        text.append("\n  variables:   ")
            .append(variables.asJsonMap())
            .append("\n  timers:      ")
            .append(timers)
            .append("\n  nextRunAt:   ")
            .append(nextRunAt.map(Instant::toString).orElse("-"));
        return text.toString();
    }

    private String history() {
        int skipped = Math.max(0, stateHistory.size() - HISTORY_LIMIT);
        var states = stateHistory.subList(skipped, stateHistory.size())
            .stream()
            .map(entry -> entry.state().name())
            .collect(Collectors.joining(" -> "));
        return skipped == 0 ? states : "(" + skipped + " earlier) -> " + states;
    }

    /** State history entry: the state and when it was entered. */
    public record StateHistoryEntry(ProcessStateId state, Instant changedAt) {
    }
}
