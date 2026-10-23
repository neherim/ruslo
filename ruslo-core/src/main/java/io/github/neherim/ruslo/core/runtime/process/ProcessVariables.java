package io.github.neherim.ruslo.core.runtime.process;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.cfg.JsonNodeFeature;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stores process variables as one JSON object: variable name to its JSON value. */
public class ProcessVariables {
    private static final TypeReference<HashMap<String, JsonNode>> VARIABLES_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Map<String, JsonNode> variables;

    private ProcessVariables(ObjectMapper objectMapper, Map<String, JsonNode> variables) {
        this.objectMapper = objectMapper;
        this.variables = variables;
    }

    public static ProcessVariables empty(ObjectMapper objectMapper) {
        return new ProcessVariables(objectMapper, new HashMap<>());
    }

    /**
     * Reads variables from a JSON object. Decimals are read as {@code BigDecimal} without losing precision or scale,
     * so {@link #getVariable} returns the stored value.
     */
    public static ProcessVariables fromString(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return empty(objectMapper);
        }
        return new ProcessVariables(objectMapper, treeReader(objectMapper).readValue(json));
    }

    public static ProcessVariables fromMap(ObjectMapper objectMapper, Map<String, Object> variables) {
        var processVariables = ProcessVariables.empty(objectMapper);
        variables.forEach(processVariables::setVariable);
        return processVariables;
    }

    private static ObjectReader treeReader(ObjectMapper objectMapper) {
        return objectMapper.readerFor(VARIABLES_TYPE)
            .with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .without(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES);
    }

    /** Serializes the variables; an empty set is stored as {@code null} (the {@code variables} column is nullable). */
    public String serializeToString() {
        if (variables.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(variables);
    }

    public ProcessVariables setVariable(String name, Object value) {
        variables.put(name, objectMapper.valueToTree(value));
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

    /** Returns the value as {@link #getVariable} would read it after {@link #setVariable}. */
    public <T> T convert(Object value, Class<T> clazz) {
        return objectMapper.treeToValue(objectMapper.valueToTree(value), clazz);
    }

    public <T> T getVariable(String name, Class<T> clazz) {
        var jsonValue = variables.get(name);
        if (jsonValue == null) {
            return null;
        }
        return objectMapper.treeToValue(jsonValue, clazz);
    }

    /** Returns an immutable map of variable name to the JSON text of its value. */
    public Map<String, String> asMap() {
        var result = new LinkedHashMap<String, String>();
        variables.forEach((name, value) -> result.put(name, objectMapper.writeValueAsString(value)));
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
