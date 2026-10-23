package io.github.neherim.ruslo.core.dispatcher;

import java.time.Instant;
import java.util.Optional;

/**
 * Selects ready process instances from the database and submits them to workers.
 *
 * <p>{@code ProcessDispatchScheduler} calls {@link #dispatch()} in a loop.
 */
public interface ProcessDispatcher {

    /**
     * Selects ready process instances and submits them.
     *
     * @return {@code true} if at least one process was submitted
     */
    boolean dispatch();

    /**
     * Returns the earliest readiness time of a process this dispatcher could claim. The scheduler uses it to wake up
     * by a timer due time instead of waiting a full poll interval.
     *
     * @return the earliest {@code next_run_at}, possibly already passed; empty if there is nothing to wait for
     */
    Optional<Instant> nextRunAt();
}
