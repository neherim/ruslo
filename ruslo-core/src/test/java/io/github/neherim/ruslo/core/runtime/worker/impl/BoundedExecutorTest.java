package io.github.neherim.ruslo.core.runtime.worker.impl;

import io.github.neherim.ruslo.core.TestAwait;
import io.github.neherim.ruslo.core.utils.thread.CurrentThreadExecutorService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedExecutorTest {

    @Test
    void releasesCapacityBeforeInvokingCompletionHook() {
        var executorReference = new AtomicReference<BoundedExecutor>();
        var capacityAtHook = new AtomicInteger(-1);
        var activeAtHook = new AtomicInteger(-1);
        var executor = new BoundedExecutor(1, new CurrentThreadExecutorService(), true, () -> {
            var boundedExecutor = executorReference.get();
            capacityAtHook.set(boundedExecutor.getAvailableCapacity());
            activeAtHook.set(boundedExecutor.getActiveTaskCount());
        });
        executorReference.set(executor);

        assertTrue(executor.trySubmit(() -> {}));

        assertEquals(1, capacityAtHook.get());
        assertEquals(0, activeAtHook.get());
        executor.close();
    }

    @Test
    void rejectedSubmissionReturnsPermitAndDoesNotInvokeCompletionHook() {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        delegate.shutdown();
        var completions = new AtomicInteger();
        var executor = new BoundedExecutor(1, delegate, false, completions::incrementAndGet);

        assertFalse(executor.trySubmit(() -> {}));

        assertEquals(1, executor.getAvailableCapacity());
        assertEquals(0, completions.get());
        executor.close();
    }

    @Test
    void executionFailureStillReleasesCapacity() {
        var executor = new BoundedExecutor(1, new CurrentThreadExecutorService(), true, () -> {});

        assertThrows(IllegalStateException.class, () -> executor.trySubmit(() -> {
            throw new IllegalStateException("execution failed");
        }));

        assertEquals(1, executor.getAvailableCapacity());
        assertEquals(0, executor.getActiveTaskCount());
        executor.close();
    }

    @Test
    void closeWaitsForAcceptedExecutionAndKeepsExternalExecutorOpen() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        var executor = new BoundedExecutor(2, delegate, false, () -> {});
        Runnable task = () -> {
            entered.countDown();
            await(release);
        };

        try {
            assertTrue(executor.trySubmit(task));
            TestAwait.awaitLatch(entered, "Execution should start");

            var closed = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                executor.close();
                closed.countDown();
            });

            awaitClosed(executor);
            assertFalse(closed.await(100, TimeUnit.MILLISECONDS));
            assertFalse(executor.trySubmit(task));
            release.countDown();
            assertTrue(closed.await(5, TimeUnit.SECONDS));

            assertEquals(0, executor.getActiveTaskCount());
            assertEquals(0, executor.getAvailableCapacity());
            assertFalse(delegate.isShutdown());
        } finally {
            release.countDown();
            delegate.shutdownNow();
        }
    }

    @Test
    void queuedAcceptedExecutionsSkipUserCodeAfterClose() throws Exception {
        var delegateStarted = new CountDownLatch(1);
        var releaseDelegate = new CountDownLatch(1);
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        delegate.execute(() -> {
            delegateStarted.countDown();
            await(releaseDelegate);
        });

        var completions = new AtomicInteger();
        var executions = new AtomicInteger();
        var executor = new BoundedExecutor(3, delegate, false, completions::incrementAndGet);
        Runnable task = executions::incrementAndGet;

        try {
            TestAwait.awaitLatch(delegateStarted, "External executor should be occupied");
            assertTrue(executor.trySubmit(task));
            assertTrue(executor.trySubmit(task));

            var closed = new CountDownLatch(1);
            Thread.ofVirtual().start(() -> {
                executor.close();
                closed.countDown();
            });
            awaitClosed(executor);
            releaseDelegate.countDown();

            assertTrue(closed.await(5, TimeUnit.SECONDS));
            assertEquals(0, executions.get());
            assertEquals(0, completions.get());
            assertEquals(0, executor.getActiveTaskCount());
        } finally {
            releaseDelegate.countDown();
            delegate.shutdownNow();
        }
    }

    @Test
    void closeTimeoutDoesNotShutdownExternalExecutor() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        var executor = new BoundedExecutor(1, delegate, false, () -> {}, Duration.ofMillis(50));
        Runnable task = () -> {
            entered.countDown();
            await(release);
        };

        try {
            assertTrue(executor.trySubmit(task));
            TestAwait.awaitLatch(entered, "Execution should start");

            executor.close();

            assertFalse(delegate.isShutdown());
            assertEquals(1, executor.getActiveTaskCount());
            assertEquals(0, executor.getAvailableCapacity());

            release.countDown();
            awaitNoActiveTasks(executor);
        } finally {
            release.countDown();
            delegate.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting for latch", ex);
        }
    }

    private static void awaitClosed(BoundedExecutor executor) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.getAvailableCapacity() != 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(0, executor.getAvailableCapacity());
    }

    private static void awaitNoActiveTasks(BoundedExecutor executor) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (executor.getActiveTaskCount() != 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(0, executor.getActiveTaskCount());
    }
}
