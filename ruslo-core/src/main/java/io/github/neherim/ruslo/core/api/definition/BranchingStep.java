package io.github.neherim.ruslo.core.api.definition;

/**
 * Step whose returned outcome selects the target state from the definition's routes.
 *
 * @param <O> outcome enum
 */
@FunctionalInterface
public interface BranchingStep<O extends Enum<O>> {
    /** Executes the step and returns a non-null outcome. */
    O execute(ProcessContext context);
}
