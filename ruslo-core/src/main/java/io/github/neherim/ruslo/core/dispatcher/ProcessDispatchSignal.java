package io.github.neherim.ruslo.core.dispatcher;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Signals the scheduler that work appeared or worker capacity freed up. At most one signal is kept: one new
 * dispatch cycle is enough for the scheduler.
 */
public final class ProcessDispatchSignal {
    private final ArrayBlockingQueue<Boolean> signals = new ArrayBlockingQueue<>(1);

    /** Wakes the waiting scheduler, or keeps one signal for its next wait. */
    public void wakeUp() {
        signals.offer(Boolean.TRUE);
    }

    /**
     * Waits for a signal at most {@code timeout}.
     *
     * @return {@code true} if a signal ended the wait
     * @throws InterruptedException if the scheduler thread is interrupted
     */
    public boolean await(Duration timeout) throws InterruptedException {
        return signals.poll(timeout.toMillis(), TimeUnit.MILLISECONDS) != null;
    }
}
