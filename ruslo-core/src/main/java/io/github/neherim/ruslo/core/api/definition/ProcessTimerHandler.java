package io.github.neherim.ruslo.core.api.definition;

/**
 * Timer handler of an {@code every}, {@code after} or {@code at} event; on success the process moves to the state
 * declared in the definition. To choose the target state, use {@link BranchingTimerHandler}.
 */
@FunctionalInterface
public interface ProcessTimerHandler {
    /** Handles the timer. */
    void handle(ProcessContext context);
}
