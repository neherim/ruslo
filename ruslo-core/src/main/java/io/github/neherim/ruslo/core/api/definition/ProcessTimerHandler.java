package io.github.neherim.ruslo.core.api.definition;

/**
 * Await timer handler for {@code every} or {@code timeout}; on success the process moves to the state declared
 * in the definition. To choose the target state of {@code every}, use {@link BranchingTimerHandler}.
 */
@FunctionalInterface
public interface ProcessTimerHandler {
    /** Handles the timer. */
    void handle(ProcessContext context);
}
