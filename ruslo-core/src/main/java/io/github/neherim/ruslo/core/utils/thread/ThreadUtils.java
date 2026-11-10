package io.github.neherim.ruslo.core.utils.thread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public class ThreadUtils {
    private static final Logger log = LoggerFactory.getLogger(ThreadUtils.class);

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
