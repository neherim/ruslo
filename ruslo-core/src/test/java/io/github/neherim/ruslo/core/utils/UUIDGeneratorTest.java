package io.github.neherim.ruslo.core.utils;

import io.github.neherim.ruslo.core.api.test.MutableClock;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.random.RandomGenerator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UUIDGeneratorTest {
    private static final Instant NOW = Instant.parse("2026-11-14T12:00:00Z");
    private final MutableClock clock = new MutableClock(ZoneOffset.UTC, NOW, ChronoUnit.MILLIS);

    @Test
    void shouldMatchRfc9562Version7Example() {
        clock.setTime(Instant.ofEpochMilli(0x017F22E279B0L));
        var generator = new UUIDGenerator(clock, randomValues(0xCC3L, 0x18C4DC0C0C07398FL));

        var uuid = generator.next();

        assertEquals(UUID.fromString("017f22e2-79b0-7cc3-98c4-dc0c0c07398f"), uuid);
        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
    }

    @Test
    void shouldMaskRandomBitsWithoutChangingTimestampVersionOrVariant() {
        var generator = new UUIDGenerator(clock, randomValues(-1, -1));

        var uuid = generator.next();

        assertEquals(clock.millis(), timestamp(uuid));
        assertEquals(7, uuid.version());
        assertEquals(2, uuid.variant());
        assertEquals(0xFFFL, uuid.getMostSignificantBits() & 0xFFFL);
        assertEquals(0xBFFFFFFFFFFFFFFFL, uuid.getLeastSignificantBits());
    }

    @Test
    void shouldIncreaseWithinOneMillisecond() {
        var generator = new UUIDGenerator(clock, randomValues(0, 0));
        var previous = generator.next();

        for (int i = 0; i < 10_000; i++) {
            var next = generator.next();
            assertBefore(previous, next);
            assertEquals(clock.millis(), timestamp(next));
            previous = next;
        }
    }

    @Test
    void shouldReseedWhenClockAdvances() {
        var generator = new UUIDGenerator(clock, randomValues(-1, -1, 0, 0));
        var previous = generator.next();
        clock.fastForwardTime(Duration.ofMillis(1));

        var next = generator.next();

        assertBefore(previous, next);
        assertEquals(clock.millis(), timestamp(next));
        assertEquals(0, next.getMostSignificantBits() & 0xFFFL);
        assertEquals(Long.MIN_VALUE, next.getLeastSignificantBits());
    }

    @Test
    void shouldKeepIncreasingUntilClockCatchesUpAfterRollback() {
        var generator = new UUIDGenerator(clock, randomValues(0, 0, 0, 0));
        var first = generator.next();
        clock.setTime(NOW.minusSeconds(60));
        var duringRollback = generator.next();
        clock.setTime(NOW);
        var caughtUp = generator.next();
        clock.setTime(NOW.plusMillis(1));
        var afterRollback = generator.next();

        assertBefore(first, duringRollback);
        assertBefore(duringRollback, caughtUp);
        assertBefore(caughtUp, afterRollback);
        assertEquals(NOW.toEpochMilli(), timestamp(duringRollback));
        assertEquals(NOW.toEpochMilli(), timestamp(caughtUp));
        assertEquals(clock.millis(), timestamp(afterRollback));
    }

    @Test
    void shouldCarryFromRandBToRandAWithoutChangingVersionOrVariant() {
        var generator = new UUIDGenerator(clock, randomValues(0x7FF, -1));
        var previous = generator.next();

        var next = generator.next();

        assertBefore(previous, next);
        assertEquals(clock.millis(), timestamp(next));
        assertEquals(7, next.version());
        assertEquals(2, next.variant());
        assertEquals(0x800L, next.getMostSignificantBits() & 0xFFFL);
        assertEquals(Long.MIN_VALUE, next.getLeastSignificantBits());
    }

    @Test
    void shouldRejectCounterExhaustionUntilClockAdvances() {
        var generator = new UUIDGenerator(clock, randomValues(-1, -1, 0, 0));
        var previous = generator.next();

        assertThrows(IllegalStateException.class, generator::next);
        assertThrows(IllegalStateException.class, generator::next);
        clock.setTime(NOW.minusMillis(1));
        assertThrows(IllegalStateException.class, generator::next);
        clock.setTime(NOW.plusMillis(1));

        assertBefore(previous, generator.next());
    }

    @Test
    void shouldGenerateUniqueOrderedUuidsAcrossThreads() throws Exception {
        var generator = new UUIDGenerator(clock, new Random(123));
        var start = new CountDownLatch(1);
        var tasks = new ArrayList<Callable<List<UUID>>>();
        for (int thread = 0; thread < 8; thread++) {
            tasks.add(() -> {
                start.await();
                var uuids = new ArrayList<UUID>();
                for (int i = 0; i < 2_000; i++) {
                    uuids.add(generator.next());
                }
                for (int i = 1; i < uuids.size(); i++) {
                    assertBefore(uuids.get(i - 1), uuids.get(i));
                }
                return uuids;
            });
        }

        var unique = new HashSet<UUID>();
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = tasks.stream().map(executor::submit).toList();
            start.countDown();
            for (var future : futures) {
                for (var uuid : future.get()) {
                    assertTrue(unique.add(uuid), "Duplicate UUID: " + uuid);
                    assertEquals(clock.millis(), timestamp(uuid));
                    assertEquals(7, uuid.version());
                    assertEquals(2, uuid.variant());
                }
            }
        }
        assertEquals(16_000, unique.size());
    }

    @Test
    void shouldGenerateVersion7UuidsThroughSharedGenerator() {
        var before = System.currentTimeMillis();
        var first = UUIDGenerator.generate();
        var second = UUIDGenerator.generate();
        var after = System.currentTimeMillis();

        assertBefore(first, second);
        assertTrue(timestamp(first) >= before);
        assertTrue(timestamp(second) <= after);
        assertEquals(7, first.version());
        assertEquals(7, second.version());
        assertEquals(2, first.variant());
        assertEquals(2, second.variant());
    }

    private static RandomGenerator randomValues(long... values) {
        return new RandomGenerator() {
            private int index;

            @Override
            public long nextLong() {
                return values[index++];
            }
        };
    }

    private static long timestamp(UUID uuid) {
        return uuid.getMostSignificantBits() >>> 16;
    }

    private static void assertBefore(UUID first, UUID second) {
        assertTrue(first.toString().compareTo(second.toString()) < 0, () -> first + " must precede " + second);
    }
}
