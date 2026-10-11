package io.github.neherim.ruslo.core.api.definition;

import java.util.Optional;
import java.util.UUID;

/**
 * Process instance as seen by a handler: identity, current state and variables.
 */
public interface ProcessContext {

    UUID processId();

    String correlationId();

    ProcessTypeId processType();

    ProcessStateId state();

    /**
     * Returns the variable deserialized from JSON into {@code clazz}, or {@code null} if it is not set.
     */
    <T> T getVariable(String name, Class<T> clazz);

    /**
     * Returns the variable, or {@code null} if it is not set.
     */
    <T> T getVariable(VariableType<T> type);

    /**
     * Sets the variable; the value is serialized to JSON.
     */
    void setVariable(String name, Object value);

    /**
     * Sets the variable; the value is serialized to JSON.
     */
    <T> void setVariable(VariableType<T> type, T value);

    /**
     * Removes the variable.
     *
     * @return {@code true} if the variable was set
     */
    boolean removeVariable(String name);

    /**
     * Removes the variable.
     *
     * @return {@code true} if the variable was set
     */
    <T> boolean removeVariable(VariableType<T> type);

    /**
     * Returns the number of failed attempts of the current step or event; {@code 0} on the first attempt.
     */
    int attempt();

    Optional<String> businessKey();
}
