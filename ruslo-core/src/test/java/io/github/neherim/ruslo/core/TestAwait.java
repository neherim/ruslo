package io.github.neherim.ruslo.core;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TestAwait {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private TestAwait() {
    }

    public static void awaitLatch(CountDownLatch latch, String message) {
        try {
            assertTrue(latch.await(DEFAULT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, e);
        }
    }

    public static void join(Thread thread, String message) {
        try {
            thread.join(DEFAULT_TIMEOUT.toMillis());
            assertTrue(!thread.isAlive(), message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, e);
        }
    }
}
