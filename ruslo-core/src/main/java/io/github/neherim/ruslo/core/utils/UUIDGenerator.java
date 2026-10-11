package io.github.neherim.ruslo.core.utils;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;

public class UUIDGenerator {
    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    /** Generates a UUIDv7 with a shared generator, monotonic within one millisecond. */
    public static UUID generate() {
        return GENERATOR.generate();
    }
}
