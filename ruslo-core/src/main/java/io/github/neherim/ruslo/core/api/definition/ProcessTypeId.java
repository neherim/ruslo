package io.github.neherim.ruslo.core.api.definition;

/**
 * Canonical process type, identified by name only. Created by {@link ProcessType#of(String)}.
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
