package io.github.neherim.ruslo.core.dispatcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Background thread that polls the database by calling {@link ProcessDispatcher#dispatch()}.
 *
 * <p>The poll interval adapts: it shrinks towards {@code minInterval} while processes are ready and grows towards
 * {@code maxInterval} while none are. If the nearest process becomes ready earlier, the scheduler wakes up by then,
 * so timers fire on time on an idle node too. {@link ProcessDispatchSignal} interrupts the wait early.
 */
public class ProcessDispatchScheduler {
    private static final Logger log = LoggerFactory.getLogger(ProcessDispatchScheduler.class);
    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(10);

    private final Duration minInterval;
    private final Duration maxInterval;
    private final ProcessDispatcher processDispatcher;
    private final ProcessDispatchSignal dispatchSignal;
    private final Clock clock;
    private Thread thread;
    private volatile boolean running;

    /**
     * Creates a scheduler woken by the shared signal of runtime events and freed worker capacity.
     *
     * @param minInterval minimum fallback interval
     * @param maxInterval maximum fallback interval
     * @param clock       the engine clock that {@code available_at} is compared against
     */
    public ProcessDispatchScheduler(
            Duration minInterval,
            Duration maxInterval,
            ProcessDispatcher processDispatcher,
            ProcessDispatchSignal dispatchSignal,
            Clock clock
    ) {
        this.processDispatcher = processDispatcher;
        this.minInterval = minInterval;
        this.maxInterval = maxInterval;
        this.dispatchSignal = dispatchSignal;
        this.clock = clock;
    }

    /**
     * Starts the polling thread.
     *
     * @throws IllegalStateException if already started
     */
    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("Process Dispatcher Scheduler is already started");
        }
        running = true;
        thread = Thread.ofPlatform().name("process-dispatcher-scheduler").start(this::dispatchLoop);
    }

    private void dispatchLoop() {
        long currentDelayMs = minInterval.toMillis();
        while (running) {
            boolean dispatched = false;
            Instant nextAvailableAt = null;
            try {
                dispatched = processDispatcher.dispatch();
                if (!dispatched) {
                    nextAvailableAt = processDispatcher.nextAvailableAt().orElse(null);
                }
            } catch (RuntimeException ex) {
                log.error("Unexpected error in dispatch loop", ex);
            }
            if (dispatched) {
                currentDelayMs = minInterval.toMillis();
                continue;
            }
            try {
                dispatchSignal.await(Duration.ofMillis(delayMs(currentDelayMs, nextAvailableAt)));
            } catch (InterruptedException e) {
                return;
            }
            currentDelayMs = Math.min(maxInterval.toMillis(), currentDelayMs * 2);
        }
    }

    /**
     * Shortens the pause to the nearest process due time. A due time already passed means a ready process could not
     * be claimed just now, so the pause stays the same to avoid polling the database in a loop.
     */
    private long delayMs(long backoffMs, Instant nextAvailableAt) {
        if (nextAvailableAt == null) {
            return backoffMs;
        }
        // +1 ms: available_at may be stored with sub-millisecond precision, and a claim requires available_at <= now.
        long untilNextMs = Duration.between(clock.instant(), nextAvailableAt).toMillis() + 1;
        return untilNextMs > 0 ? Math.min(backoffMs, untilNextMs) : backoffMs;
    }

    /**
     * Stops the scheduler and waits for the current dispatch cycle to finish.
     * It may be restarted with {@link #start()}.
     */
    public synchronized void close() {
        if (thread == null) {
            return;
        }
        running = false;
        thread.interrupt();
        try {
            if (!thread.join(STOP_TIMEOUT)) {
                log.error("Process dispatcher scheduler did not stop in {}", STOP_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread = null;
    }
}
