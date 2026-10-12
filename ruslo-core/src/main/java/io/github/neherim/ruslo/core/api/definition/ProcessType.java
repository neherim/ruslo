package io.github.neherim.ruslo.core.api.definition;

/**
 * Process type, usually implemented by an enum or created by {@link #of(String)}.
 * <p>
 * Types are identified by {@link #name()} only: objects with the same name are the same type regardless of
 * their Java type. Compare with {@link #is(ProcessType)} or {@link #is(String)}, not {@code ==} or {@code equals}.
 */
public interface ProcessType {

    String name();

    /** Returns {@code true} if {@code other} has the same name. */
    default boolean is(ProcessType other) {
        return other != null && name().equals(other.name());
    }

    /** Returns {@code true} if this type is named {@code typeName}. */
    default boolean is(String typeName) {
        return name().equals(typeName);
    }

    /** Returns the canonical type with the same name. */
    default ProcessTypeId id() {
        return new ProcessTypeId(name());
    }

    /** Returns the canonical type with the given name. */
    static ProcessTypeId of(String name) {
        return new ProcessTypeId(name);
    }
}
