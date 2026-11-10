package io.github.neherim.ruslo.core.api.definition;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Gives a handler access to the process it runs in: identity, current state, variables and timers.
 */
public interface ProcessContext {

    UUID processId();

    /**
     * Returns the correlation id used in logs and search: the one given at process start, otherwise taken from the
     * MDC of the starting thread or generated.
     */
    String correlationId();

    ProcessType processType();

    ProcessState state();

    /**
     * Returns the variable.
     *
     * @throws IllegalStateException if the variable is not set; use {@link #findVariable} for an optional one
     */
    default <T> T getVariable(VariableType<T> type) {
        return findVariable(type).orElseThrow(
                () -> new IllegalStateException("Variable '" + type.name() + "' is not set"));
    }

    /**
     * Returns the variable, empty if it is not set or holds {@code null}.
     */
    <T> Optional<T> findVariable(VariableType<T> type);

    /**
     * Sets the variable.
     */
    <T> void setVariable(VariableType<T> type, T value);

    /**
     * Removes the variable.
     *
     * @return {@code true} if the variable was set
     */
    <T> boolean removeVariable(VariableType<T> type);

    /**
     * Sets the process timer to {@code moment}. The moment is stored truncated to microseconds, and {@link #findTimer}
     * returns the truncated value. When the timer fires and how long it lives: see {@link TimerType}.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     * @throws NullPointerException     if {@code moment} is {@code null}; only {@link #cancelTimer} removes a timer
     */
    void setTimer(TimerType timer, Instant moment);

    /**
     * Cancels the process timer, see {@link TimerType}.
     *
     * @return {@code true} if the timer was set
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     */
    boolean cancelTimer(TimerType timer);

    /**
     * Returns the moment of the process timer, empty if it is not set, see {@link TimerType}.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     */
    Optional<Instant> findTimer(TimerType timer);

    /**
     * Returns the number of failed attempts of the current step or event; {@code 0} on the first attempt.
     */
    int attempt();

    /**
     * Returns the business key given at process start, empty if none was given.
     */
    Optional<String> businessKey();

    /**
     * Returns the current time of the engine clock, the clock that timers and retries use. Use it instead of
     * {@code Instant.now()}: in tests the emulator controls it.
     */
    Instant now();
}
