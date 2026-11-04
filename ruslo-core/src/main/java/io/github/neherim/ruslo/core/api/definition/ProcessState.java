package io.github.neherim.ruslo.core.api.definition;

/**
 * Process state, usually implemented by an enum or created by {@link #of(String)}.
 * <p>
 * States are identified by {@link #name()} only: objects with the same name are the same state regardless of
 * their Java type.
 * <p>
 * A state the engine returns (views, {@link ProcessContext#state()}, exceptions) is the object declared in
 * {@link ProcessSpecBuilder}, so {@code equals} and {@code ==} with the declared constants work. If the current
 * definition declares no such state, or its type is not registered on this node, the engine returns
 * {@link #of(String)}. Use {@link #is(ProcessState)} or {@link #is(String)} to compare with {@code of(...)} or
 * a name.
 */
public interface ProcessState {

    String name();

    /**
     * Returns a human-readable description for the admin UI and the process diagram; {@link #name()} by default.
     * Taken from the object passed to {@link ProcessSpecBuilder}; does not affect identity.
     */
    default String description() {
        return name();
    }

    /** Returns {@code true} if {@code other} has the same name. */
    default boolean is(ProcessState other) {
        return other != null && name().equals(other.name());
    }

    /** Returns {@code true} if this state is named {@code stateName}. */
    default boolean is(String stateName) {
        return name().equals(stateName);
    }

    /** Returns the canonical state with the same name. */
    default ProcessStateId id() {
        return new ProcessStateId(name());
    }

    /** Returns the canonical state with the given name. */
    static ProcessStateId of(String name) {
        return new ProcessStateId(name);
    }
}
