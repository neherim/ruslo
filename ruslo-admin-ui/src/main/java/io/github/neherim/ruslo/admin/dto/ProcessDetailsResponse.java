package io.github.neherim.ruslo.admin.dto;

import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Process page data, without the internal signal queue.
 *
 * @param stateDescription description of the current state if it differs from the state name
 */
public record ProcessDetailsResponse(UUID id, String type, String state, String stateDescription,
                                     ProcessStatus status,
                                     Instant scheduledAt, String businessKey, String correlationId,
                                     int attempt, String lastError,
                                     List<StateHistoryResponse> stateHistory,
                                     Map<String, String> variables,
                                     String stateKind, List<String> acceptedMessageTypes,
                                     String timerDelay) {
    public int variablesCount() { return variables.size(); }
    public int historyCount() { return stateHistory.size(); }
    /** Returns whether to show the message form: only for an await, including one stopped in {@code FAILED}. */
    public boolean acceptsMessages() {
        return "AWAIT".equals(stateKind) && !acceptedMessageTypes.isEmpty();
    }

    /** Returns whether the variable is a JSON object or array, shown collapsed to one line; a scalar is shown as is. */
    public boolean structuredVariable(String name) {
        var value = variables.get(name);
        return value != null && (value.startsWith("{") || value.startsWith("["));
    }

    /**
     * Returns the version of the mutable fields: polling does not re-render the page until it changes.
     * The status is hashed by name so the version does not depend on the JVM.
     */
    public String version() {
        return Integer.toHexString(Objects.hash(state, status.name(), scheduledAt, businessKey, attempt,
                lastError, stateHistory, variables));
    }
}
