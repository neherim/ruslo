package io.github.neherim.ruslo.core.management;

import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Builds a JSON skeleton of a class so an operator typing a payload by hand only fills in values. Properties are
 * taken from the engine's mapper, so names and ignored properties match the conversion of the payload.
 */
public final class JsonTemplate {
    private final ObjectMapper mapper;

    public JsonTemplate(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper must not be null");
    }

    /**
     * Returns the pretty-printed skeleton of {@code type}, see
     * {@link io.github.neherim.ruslo.core.api.ProcessEngineAdmin#messagePayloadTemplate}.
     */
    public String of(Class<?> type) {
        return mapper.writerWithDefaultPrettyPrinter()
            .writeValueAsString(valueOf(mapper.constructType(type), new HashSet<>()));
    }

    private JsonNode valueOf(JavaType type, Set<Class<?>> path) {
        var nodes = mapper.getNodeFactory();
        var raw = type.getRawClass();
        if (raw == boolean.class || raw == Boolean.class) return nodes.booleanNode(false);
        if (raw == char.class || raw == Character.class || CharSequence.class.isAssignableFrom(raw)) {
            return nodes.stringNode("");
        }
        if (type.isPrimitive() || Number.class.isAssignableFrom(raw)) return nodes.numberNode(0);
        if (type.isArrayType() || type.isCollectionLikeType()) return nodes.arrayNode();
        if (type.isMapLikeType()) return nodes.objectNode();
        if (!isBean(type) || !path.add(raw)) return nodes.nullNode();
        try {
            var properties = mapper._serializationContext().introspectBeanDescription(type).findProperties();
            if (properties.isEmpty()) return nodes.nullNode();
            var object = nodes.objectNode();
            properties.forEach(property -> object.set(property.getName(), valueOf(property.getPrimaryType(), path)));
            return object;
        } finally {
            path.remove(raw);
        }
    }

    /** Enums, dates, ids and other JDK or Jackson scalars have no fields to fill in. */
    private boolean isBean(JavaType type) {
        var raw = type.getRawClass();
        return !type.isEnumType() && !raw.getName().startsWith("java.") && !JsonNode.class.isAssignableFrom(raw);
    }
}
