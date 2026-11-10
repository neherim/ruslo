package io.github.neherim.ruslo.core.api.definition;

/**
 * Process type that holds only the name. Created by {@link ProcessType#of(String)} and {@link ProcessType#id()}.
 */
public record ProcessTypeId(String name) implements ProcessType {

    public ProcessTypeId {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Process type name cannot be blank");
        }
    }

    /** Returns {@code this}. */
    @Override
    public ProcessTypeId id() {
        return this;
    }
}
