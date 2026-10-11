package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.VariableType;

import java.util.Map;
import java.util.Optional;

/**
 * Snapshot of process variables taken when {@link ProcessInstanceDetails} was read. Values are deserialized with
 * the engine's {@code ObjectMapper}.
 */
public interface ProcessVariablesView {

    /**
     * Returns the variable, or empty if it is not set.
     *
     * @throws tools.jackson.core.JacksonException if the stored value cannot be read as the variable's class
     */
    <T> Optional<T> get(VariableType<T> type);

    /** Returns an immutable map of variable names to JSON text. */
    Map<String, String> asJsonMap();
}
