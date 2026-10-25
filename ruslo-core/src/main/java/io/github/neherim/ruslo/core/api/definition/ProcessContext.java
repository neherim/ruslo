package io.github.neherim.ruslo.core.api.definition;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Process instance as seen by a handler: identity, current state, variables and timers.
 */
public interface ProcessContext {

    UUID processId();

    String correlationId();

    ProcessTypeId processType();

    ProcessStateId state();

    /**
     * Returns the variable deserialized from JSON into {@code clazz}, or {@code null} if it is not set.
     */
    <T> T getVariable(String name, Class<T> clazz);

    /**
     * Returns the variable, or {@code null} if it is not set.
     */
    <T> T getVariable(VariableType<T> type);

    /**
     * Sets the variable; the value is serialized to JSON.
     */
    void setVariable(String name, Object value);

    /**
     * Sets the variable; the value is serialized to JSON.
     */
    <T> void setVariable(VariableType<T> type, T value);

    /**
     * Removes the variable.
     *
     * @return {@code true} if the variable was set
     */
    boolean removeVariable(String name);

    /**
     * Removes the variable.
     *
     * @return {@code true} if the variable was set
     */
    <T> boolean removeVariable(VariableType<T> type);

    /**
     * Sets the process timer to {@code moment}, replacing its previous moment. The moment is stored truncated to
     * microseconds, and {@link #getTimer} returns the truncated value. An {@code at} of an await fires when the
     * process is in that await and the timer is due; a past moment fires at once. State transitions do not touch
     * timers: the timer lives until it fires or is {@link #cancelTimer cancelled}, also when the process leaves the
     * await by another event. A fired timer is cancelled before its handler runs; if the handler fails, the
     * transaction is rolled back and the retry sees the timer again. The handler does not get the fired moment:
     * keep it in a variable if it is business data.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     * @throws NullPointerException     if {@code moment} is {@code null}; only {@link #cancelTimer} removes a timer
     */
    void setTimer(TimerType timer, Instant moment);

    /**
     * Cancels the process timer, see {@link #setTimer}.
     *
     * @return {@code true} if the timer was set
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     */
    boolean cancelTimer(TimerType timer);

    /**
     * Returns the moment of the process timer, empty if it is not set, see {@link #setTimer}.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     */
    Optional<Instant> getTimer(TimerType timer);

    /**
     * Returns the number of failed attempts of the current step or event; {@code 0} on the first attempt.
     */
    int attempt();

    Optional<String> businessKey();

    /**
     * Returns the current time of the engine clock, the clock that timers and retries use. Use it instead of
     * {@code Instant.now()}: in tests the emulator controls it.
     */
    Instant now();
}
