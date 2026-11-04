package io.github.neherim.ruslo.core.api.definition;

/**
 * Canonical process type, identified by name only. Created by {@link ProcessType#of(String)}; the engine returns
 * it for a type declared by name and for a type not registered on this node (see {@link ProcessType}).
 */
public record ProcessTypeId(String name) implements ProcessType {

    public ProcessTypeId {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("ProcessTypeId must not be blank");
        }
    }

    /** Returns {@code this}. */
    @Override
    public ProcessTypeId id() {
        return this;
    }
}
