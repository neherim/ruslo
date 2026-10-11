package io.github.neherim.ruslo.core.api.definition;

/**
 * Worker that executes a state. Stored with the process instance, so the name must stay stable across releases.
 */
public record WorkerId(String name) {
    public static final WorkerId DEFAULT = new WorkerId("DEFAULT");

    public WorkerId {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("WorkerId must not be blank");
        }
    }
}
