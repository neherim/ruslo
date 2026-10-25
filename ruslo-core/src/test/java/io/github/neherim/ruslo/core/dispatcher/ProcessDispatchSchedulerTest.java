package io.github.neherim.ruslo.core.dispatcher;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessDispatchSchedulerTest {

    @Test
    void wakeUpShouldInterruptPollingDelayWithoutLosingSignal() {
        var dispatcher = new BacklogDispatcher(0);
        var signal = new ProcessDispatchSignal();
        var scheduler = new ProcessDispatchScheduler(Duration.ofSeconds(30), Duration.ofSeconds(30), dispatcher, signal,
                Clock.systemUTC());
        try {
            scheduler.start();
            await().atMost(Duration.ofSeconds(2)).until(() -> dispatcher.cycles.get() == 1);

            signal.wakeUp();

            await().atMost(Duration.ofSeconds(2)).until(() -> dispatcher.cycles.get() >= 2);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void backlogShouldDrainWithoutWaitingBetweenSuccessfulCycles() {
        var dispatcher = new BacklogDispatcher(3);
        var scheduler = new ProcessDispatchScheduler(Duration.ofSeconds(30), Duration.ofSeconds(30), dispatcher,
                new ProcessDispatchSignal(), Clock.systemUTC());
        try {
            scheduler.start();

            await().atMost(Duration.ofSeconds(2)).until(() -> dispatcher.cycles.get() >= 4);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void shouldWakeUpWhenNextProcessBecomesAvailable() {
        var dispatcher = new BacklogDispatcher(0);
        dispatcher.nextRunAt = Instant.now().plusMillis(300);
        var scheduler = new ProcessDispatchScheduler(Duration.ofSeconds(30), Duration.ofSeconds(30), dispatcher,
                new ProcessDispatchSignal(), Clock.systemUTC());
        try {
            scheduler.start();

            await().atMost(Duration.ofSeconds(5)).until(() -> dispatcher.cycles.get() >= 2);
        } finally {
            scheduler.close();
        }
    }

    @Test
    void pastNextRunAtShouldKeepBackoff() throws InterruptedException {
        var dispatcher = new BacklogDispatcher(0);
        dispatcher.nextRunAt = Instant.now().minusSeconds(1);
        var scheduler = new ProcessDispatchScheduler(Duration.ofSeconds(30), Duration.ofSeconds(30), dispatcher,
                new ProcessDispatchSignal(), Clock.systemUTC());
        try {
            scheduler.start();
            await().atMost(Duration.ofSeconds(2)).until(() -> dispatcher.cycles.get() == 1);

            Thread.sleep(300);

            assertEquals(1, dispatcher.cycles.get());
        } finally {
            scheduler.close();
        }
    }

    @Test
    void closeShouldWaitForActiveDispatchAndStopPolling() throws Exception {
        var dispatchEntered = new CountDownLatch(1);
        var allowDispatch = new CountDownLatch(1);
        var cycles = new AtomicInteger();
        var dispatcher = new ProcessDispatcher() {
            @Override
            public boolean dispatch() {
                cycles.incrementAndGet();
                dispatchEntered.countDown();
                awaitUninterruptibly(allowDispatch);
                return true;
            }

            @Override
            public Optional<Instant> nextRunAt() {
                return Optional.empty();
            }
        };
        var scheduler = new ProcessDispatchScheduler(Duration.ofMillis(10), Duration.ofMillis(10), dispatcher,
                new ProcessDispatchSignal(), Clock.systemUTC());
        scheduler.start();
        assertTrue(dispatchEntered.await(10, TimeUnit.SECONDS));

        try (var executor = Executors.newSingleThreadExecutor()) {
            var close = executor.submit(scheduler::close);
            Thread.sleep(100);
            assertFalse(close.isDone());

            allowDispatch.countDown();
            close.get(10, TimeUnit.SECONDS);
        }

        var cyclesAfterClose = cycles.get();
        Thread.sleep(100);
        assertEquals(cyclesAfterClose, cycles.get());
    }

    @Test
    void wakeUpSignalShouldCoalesceStorm() throws InterruptedException {
        var signal = new ProcessDispatchSignal();

        for (int index = 0; index < 100; index++) {
            signal.wakeUp();
        }

        assertTrue(signal.await(Duration.ZERO));
        assertFalse(signal.await(Duration.ZERO));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        var interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ex) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BacklogDispatcher implements ProcessDispatcher {
        private final AtomicInteger remaining;
        private final AtomicInteger cycles = new AtomicInteger();
        private volatile Instant nextRunAt;

        private BacklogDispatcher(int successfulCycles) {
            remaining = new AtomicInteger(successfulCycles);
        }

        @Override
        public boolean dispatch() {
            cycles.incrementAndGet();
            return remaining.getAndUpdate(value -> Math.max(0, value - 1)) > 0;
        }

        @Override
        public Optional<Instant> nextRunAt() {
            return Optional.ofNullable(nextRunAt);
        }
    }
}
