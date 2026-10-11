package io.github.neherim.ruslo.core.api.definition;

/**
 * Process definition: declares states, transitions and handlers of one process type.
 */
@FunctionalInterface
public interface ProcessDefinition {

    /**
     * Builds the process specification. Called once, at registration.
     */
    ProcessSpec define(ProcessSpecBuilder builder);
}
