package io.github.neherim.ruslo.core.api.definition;

/**
 * Step that chooses the next state: it returns an outcome, and the definition maps each outcome to a state with
 * {@link ProcessSpecBuilder.Routes#on}.
 *
 * @param <O> outcome enum
 */
@FunctionalInterface
public interface BranchingStep<O extends Enum<O>> {
    /**
     * Executes the step and returns a non-null outcome.
     */
    O execute(ProcessContext context);
}
