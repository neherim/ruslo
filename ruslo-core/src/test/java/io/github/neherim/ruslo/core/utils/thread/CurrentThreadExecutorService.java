package io.github.neherim.ruslo.core.utils.thread;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Test ExecutorService that runs tasks in the calling thread.
 */
public class CurrentThreadExecutorService extends AbstractExecutorService {
    private volatile boolean terminated;

    @Override
    public void shutdown() {
        terminated = true;
    }

    @Override
    public boolean isShutdown() {
        return terminated;
    }

    @Override
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        shutdown();
        return true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        terminated = true;
        return Collections.emptyList();
    }

    @Override
    public void execute(Runnable command) {
        command.run();
    }
}
