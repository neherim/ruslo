package io.github.neherim.ruslo.core.api.definition;

/**
 * Timer handler of an {@code every}, {@code after} or {@code at} event that chooses the next state: it returns an
 * outcome, and the definition maps each outcome to a state with {@link ProcessSpecBuilder.Routes#on}.
 *
 * @param <O> outcome enum
 */
@FunctionalInterface
public interface BranchingTimerHandler<O extends Enum<O>> {
    /**
     * Handles the timer and returns a non-null outcome.
     */
    O handle(ProcessContext context);
}
