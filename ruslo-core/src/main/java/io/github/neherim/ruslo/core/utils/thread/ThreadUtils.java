package io.github.neherim.ruslo.core.utils.thread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ThreadUtils {
    private static final Logger log = LoggerFactory.getLogger(ThreadUtils.class);

    public static void gracefulShutdown(ExecutorService executorService, Duration timeout) {
        if (executorService != null) {
            try {
                // Disable new tasks from being submitted
                executorService.shutdown();
                // Wait a while for existing tasks to terminate
                if (!executorService.awaitTermination(timeout.getSeconds(), TimeUnit.SECONDS)) {
                    // Cancel currently executing tasks
                    executorService.shutdownNow();
                    // Wait a while for tasks to respond to being cancelled
                    if (!executorService.awaitTermination(timeout.getSeconds(), TimeUnit.SECONDS)) {
                        log.error("ExecutorService did not terminate");
                    }
                }
            } catch (InterruptedException ie) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("Interrupted while stopping the ExecutorService", ie);
            }
        }
    }

    public static void immediateShutdown(ExecutorService executorService, Duration timeout) {
        if (executorService != null) {
            try {
                executorService.shutdownNow();
                if (!executorService.awaitTermination(timeout.getSeconds(), TimeUnit.SECONDS)) {
                    log.error("ExecutorService did not terminate");
                }
            } catch (InterruptedException ie) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("Interrupted while stopping the ExecutorService", ie);
            }
        }
    }
}
