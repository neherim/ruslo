package io.github.neherim.ruslo.core.api.definition;

/**
 * Action for a step or timer event with a single target state declared in the definition.
 * To select a target by outcome, use {@link BranchingAction}.
 */
@FunctionalInterface
public interface ProcessAction {
    /** Executes the action. */
    void execute(ProcessContext ctx);
}
