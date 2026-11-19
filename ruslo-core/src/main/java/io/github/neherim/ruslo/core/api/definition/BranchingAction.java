package io.github.neherim.ruslo.core.api.definition;

/**
 * Action for a step or timer event that returns an outcome. The definition maps each outcome to a target state
 * with {@link ProcessSpecBuilder.Routes#on}.
 *
 * @param <O> outcome enum
 */
@FunctionalInterface
public interface BranchingAction<O extends Enum<O>> {
    /**
     * Executes the action and returns a non-null outcome.
     */
    O execute(ProcessContext context);
}
