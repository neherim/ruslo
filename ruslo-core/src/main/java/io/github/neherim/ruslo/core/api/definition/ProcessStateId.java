package io.github.neherim.ruslo.core.api.definition;

/**
 * Canonical process state, identified by name only. Created by {@link ProcessState#of(String)}; the engine returns
 * it for a state declared with {@code of(...)} and for a state the current definition does not declare (see
 * {@link ProcessState}).
 */
public record ProcessStateId(String name) implements ProcessState {

    public ProcessStateId {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProcessStateId must not be blank");
        }
    }

    /** Returns {@code this}. */
    @Override
    public ProcessStateId id() {
        return this;
    }
}
