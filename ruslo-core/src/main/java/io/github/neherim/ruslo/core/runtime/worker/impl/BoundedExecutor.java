package io.github.neherim.ruslo.core.runtime.worker.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Executor that limits the number of accepted tasks, without a local queue. A task is accepted only if a slot is
 * free; when the task finishes, the slot is released and {@code completionHook} is called. On close it waits for
 * accepted tasks and shuts down only an {@link ExecutorService} owned by the library.
 */
final class BoundedExecutor implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(BoundedExecutor.class);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(20);

    private final int maxConcurrency;
    private final Semaphore slots;
    private final ExecutorService executor;
    private final boolean executorOwned;
    private final Runnable completionHook;
    private final Duration shutdownTimeout;
    private volatile boolean accepting = true;

    BoundedExecutor(int maxConcurrency, ExecutorService executor, boolean executorOwned, Runnable completionHook) {
        this(maxConcurrency, executor, executorOwned, completionHook, DEFAULT_SHUTDOWN_TIMEOUT);
    }

    BoundedExecutor(
            int maxConcurrency,
            ExecutorService executor,
            boolean executorOwned,
            Runnable completionHook,
            Duration shutdownTimeout
    ) {
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("maxConcurrency must be greater than 0");
        }
        this.executor = Objects.requireNonNull(executor, "Executor must not be null");
        this.completionHook = Objects.requireNonNull(completionHook, "Completion hook must not be null");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "Shutdown timeout must not be null");
        this.maxConcurrency = maxConcurrency;
        this.slots = new Semaphore(maxConcurrency);
        this.executorOwned = executorOwned;
    }

    /**
     * Accepts the task if the executor is open and a slot is free. A task accepted before close but not started
     * by then is not executed.
     *
     * @return {@code true} if the task was accepted
     */
    boolean trySubmit(Runnable task) {
        Objects.requireNonNull(task, "Task must not be null");
        if (!accepting || !slots.tryAcquire()) {
            return false;
        }
        try {
            executor.execute(() -> run(task));
            return true;
        } catch (RejectedExecutionException ex) {
            slots.release();
            return false;
        }
    }

    int getAvailableCapacity() {
        return accepting ? slots.availablePermits() : 0;
    }

    int getActiveTaskCount() {
        return maxConcurrency - slots.availablePermits();
    }

    /**
     * Stops accepting tasks and waits for accepted ones, at most the shutdown timeout. An owned
     * {@link ExecutorService} is then shut down, interrupting unfinished tasks.
     */
    @Override
    public void close() {
        accepting = false;
        var drained = acquireAllSlots();
        if (drained) {
            slots.release(maxConcurrency);
        }
        if (executorOwned) {
            executor.shutdownNow();
        } else if (!drained) {
            log.warn("Timed out waiting for worker tasks on an external ExecutorService");
        }
    }

    private void run(Runnable task) {
        try {
            if (accepting) {
                task.run();
            }
        } finally {
            slots.release();
            // The hook runs after the slot is released so the dispatcher sees the free capacity.
            if (accepting) {
                try {
                    completionHook.run();
                } catch (RuntimeException ex) {
                    log.warn("Worker completion hook failed", ex);
                }
            }
        }
    }

    /** Waits for accepted tasks: all slots are free only when no task is active. */
    private boolean acquireAllSlots() {
        try {
            return slots.tryAcquire(maxConcurrency, shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while waiting for worker tasks", ex);
            return false;
        }
    }
}
