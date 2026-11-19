package io.github.neherim.ruslo.core.api.definition;

/**
 * Action for a step or timer event. On success, the process moves to the target state declared in the definition,
 * which can be the current state.
 * To select a target by outcome, use {@link BranchingAction}.
 */
@FunctionalInterface
public interface LinearAction {
    /** Executes the action. */
    void execute(ProcessContext ctx);
}
