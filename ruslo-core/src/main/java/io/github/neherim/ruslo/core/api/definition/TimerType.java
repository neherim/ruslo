package io.github.neherim.ruslo.core.api.definition;

/**
 * Named process timer: a moment that the code sets and that an {@code at} event of an await waits for. Unlike
 * {@code every} and {@code after}, the engine never sets it and it is not tied to one entry of an await.
 * <ul>
 * <li>The timer is set by {@link ProcessContext#setTimer},
 * {@link io.github.neherim.ruslo.core.api.ProcessStartBuilder#timer} or
 * {@link io.github.neherim.ruslo.core.api.ProcessEngineAdmin#setProcessTimer}. A timer nobody sets never fires: an
 * await whose only way out is its {@code at} waits forever.</li>
 * <li>An {@code at} fires when the process is in its await and the timer is due; a past moment fires at once, also
 * on entering the await.</li>
 * <li>State transitions do not touch timers: the timer lives until it fires or is
 * {@link ProcessContext#cancelTimer cancelled}, also after the process leaves the await by another event, and fires
 * in the next await that declares it. If it is no longer needed, the handler that leaves the await cancels it.</li>
 * <li>A fired timer is cancelled before its handler runs, so the handler may set it again to make a schedule; if the
 * handler fails, the transaction is rolled back and the retry sees the timer again. The handler does not get the
 * fired moment: keep it in a variable if it is business data.</li>
 * </ul>
 *
 * @param name timer name
 */
public record TimerType(String name) {

    /**
     * @throws IllegalArgumentException if the name is blank
     */
    public TimerType {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Timer name cannot be blank");
        }
    }

    public static TimerType of(String name) {
        return new TimerType(name);
    }
}
