package io.github.neherim.ruslo.core.api.definition;

/**
 * Step; on success the process moves to the state declared in the definition.
 * To choose the target state, use {@link BranchingStep}.
 */
@FunctionalInterface
public interface ProcessStep {
    /** Executes the step. */
    void execute(ProcessContext context);
}
