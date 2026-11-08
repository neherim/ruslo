package io.github.neherim.ruslo.core.runtime.process;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.github.neherim.ruslo.core.utils.JsonUtils.DEFAULT_OBJECT_MAPPER;

/** Stores process variables as one JSON object: variable name to its JSON value. */
public class ProcessVariables {
    private static final ObjectReader JSON_READER = DEFAULT_OBJECT_MAPPER.readerFor(
            new TypeReference<HashMap<String, JsonNode>>() {
            });

    private final Map<String, JsonNode> variables;

    private ProcessVariables(Map<String, JsonNode> variables) {
        this.variables = variables;
    }

    /** Creates an empty set of variables. */
    public static ProcessVariables empty() {
        return new ProcessVariables(new HashMap<>());
    }

    /**
     * Reads variables from a JSON object, preserving decimal precision and scale.
     */
    public static ProcessVariables fromString(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        return new ProcessVariables(JSON_READER.readValue(json));
    }

    /** Creates variables from their initial values. */
    public static ProcessVariables fromMap(Map<String, Object> variables) {
        var processVariables = ProcessVariables.empty();
        variables.forEach(processVariables::setVariable);
        return processVariables;
    }

    /** Serializes the variables; an empty set is stored as {@code null} (the {@code variables} column is nullable). */
    public String serializeToString() {
        if (variables.isEmpty()) {
            return null;
        }
        return DEFAULT_OBJECT_MAPPER.writeValueAsString(variables);
    }

    public ProcessVariables setVariable(String name, Object value) {
        variables.put(name, DEFAULT_OBJECT_MAPPER.valueToTree(value));
        return this;
    }

    /**
     * Removes the variable.
     *
     * @return {@code true} if the variable was set
     */
    public boolean removeVariable(String name) {
        return variables.remove(name) != null;
    }

    public <T> T getVariable(String name, Class<T> clazz) {
        var jsonValue = variables.get(name);
        if (jsonValue == null) {
            return null;
        }
        return DEFAULT_OBJECT_MAPPER.treeToValue(jsonValue, clazz);
    }

    /** Returns an immutable map of variable name to the JSON text of its value. */
    public Map<String, String> asMap() {
        var result = new LinkedHashMap<String, String>();
        variables.forEach((name, value) -> result.put(name, DEFAULT_OBJECT_MAPPER.writeValueAsString(value)));
        return Map.copyOf(result);
    }

    /** Variables are equal if their names and JSON values are equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof ProcessVariables other && variables.equals(other.variables);
    }

    @Override
    public int hashCode() {
        return variables.hashCode();
    }

    @Override
    public String toString() {
        return variables.toString();
    }
}
