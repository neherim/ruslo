package io.github.neherim.ruslo.core.api.definition;

/**
 * {@code every} timer handler whose returned outcome selects the target state from the definition's routes.
 *
 * @param <O> outcome enum
 */
@FunctionalInterface
public interface BranchingTimerHandler<O extends Enum<O>> {
    /** Handles the timer and returns a non-null outcome. */
    O handle(ProcessContext context);
}
