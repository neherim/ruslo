package io.github.neherim.ruslo.core.api.definition;

/**
 * Process state that holds only the name. Created by {@link ProcessState#of(String)} and {@link ProcessState#id()}.
 */
public record ProcessStateId(String name) implements ProcessState {

    public ProcessStateId {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Process state name cannot be blank");
        }
    }

    /** Returns {@code this}. */
    @Override
    public ProcessStateId id() {
        return this;
    }
}
