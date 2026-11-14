package io.github.neherim.ruslo.core.utils;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** Preserves UUID ordering for history entries with equal timestamps, including during clock rollback. */
public final class UUIDGenerator {
    private static final long RAND_A_MASK = 0xFFFL;
    private static final long RAND_B_MASK = 0x3FFFFFFFFFFFFFFFL;
    private static final UUIDGenerator GENERATOR = new UUIDGenerator(Clock.systemUTC(), new SecureRandom());

    private final Clock clock;
    private final RandomGenerator random;
    private long lastTimestamp = -1;
    private long randA;
    private long randB;

    UUIDGenerator(Clock clock, RandomGenerator random) {
        this.clock = clock;
        this.random = random;
    }

    /**
     * Generates a UUIDv7 with a shared, thread-safe generator, strictly increasing even if the clock moves backward.
     * Ordering does not extend across JVMs or restarts.
     *
     * @throws IllegalStateException if the 74-bit counter is exhausted before the clock advances
     */
    public static UUID generate() {
        return GENERATOR.next();
    }

    synchronized UUID next() {
        var timestamp = clock.millis();
        // RFC 9562, Sections 5.7 and 6.2: a randomly seeded counter spanning rand_a and rand_b.
        if (timestamp > lastTimestamp) {
            randA = random.nextLong() & RAND_A_MASK;
            randB = random.nextLong() & RAND_B_MASK;
            lastTimestamp = timestamp;
        } else if (randB < RAND_B_MASK) {
            randB++;
        } else if (randA < RAND_A_MASK) {
            randA++;
            randB = 0;
        } else {
            throw new IllegalStateException("UUIDv7 counter exhausted before the clock advanced");
        }
        return new UUID((lastTimestamp << 16) | 0x7000L | randA, Long.MIN_VALUE | randB);
    }
}
