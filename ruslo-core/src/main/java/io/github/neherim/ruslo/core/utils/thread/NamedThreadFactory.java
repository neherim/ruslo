package io.github.neherim.ruslo.core.utils.thread;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class NamedThreadFactory implements ThreadFactory {
    private final String threadNamePrefix;
    private final AtomicInteger counter = new AtomicInteger(0);

    public NamedThreadFactory(String threadNamePrefix) {
        this.threadNamePrefix = threadNamePrefix;
    }

    @Override
    public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, threadNamePrefix + "-" + counter.getAndIncrement());
        thread.setDaemon(true);
        return thread;
    }
}
