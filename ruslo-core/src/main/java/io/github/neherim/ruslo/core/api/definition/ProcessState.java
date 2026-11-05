package io.github.neherim.ruslo.core.api.definition;

/**
 * Process state, usually implemented by an enum or created by {@link #of(String)}.
 * <p>
 * States are identified by {@link #name()} only: objects with the same name are the same state regardless of
 * their Java type. The engine returns the declared object, or {@link #of(String)} if the definition does not
 * declare it.
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

    /** Returns the canonical state with the same name. */
    default ProcessStateId id() {
        return new ProcessStateId(name());
    }

    /** Returns the canonical state with the given name. */
    static ProcessStateId of(String name) {
        return new ProcessStateId(name);
    }
}
