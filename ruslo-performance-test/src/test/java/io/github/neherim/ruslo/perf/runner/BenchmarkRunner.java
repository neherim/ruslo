package io.github.neherim.ruslo.perf.runner;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/** Runs warmup and a fixed number of measured iterations of a benchmark scenario. */
public final class BenchmarkRunner {
    private BenchmarkRunner() {
    }

    /** Runs warmup; its results are not included in the returned list. */
    public static <T> List<T> run(int warmupIterations, int measuredIterations, IntFunction<T> iteration) {
        if (warmupIterations < 0 || measuredIterations < 1) {
            throw new IllegalArgumentException("Warmups must be non-negative and measured iterations positive");
        }
        for (int index = 0; index < warmupIterations; index++) {
            iteration.apply(-(index + 1));
        }
        var results = new ArrayList<T>(measuredIterations);
        for (int index = 0; index < measuredIterations; index++) {
            results.add(iteration.apply(index));
        }
        return List.copyOf(results);
    }

    /** Runs the number of iterations set by the shared benchmark runner system properties. */
    public static <T> List<T> run(IntFunction<T> iteration) {
        return run(warmupIterations(), measuredIterations(), iteration);
    }

    /** Returns the positive number of processes per measurement. */
    public static int processCount() {
        var count = Integer.getInteger("ruslo.perf.process-count", 10_000);
        if (count < 1) throw new IllegalArgumentException("Process count must be positive");
        return count;
    }

    /** Returns the number of warmup iterations from a system property. */
    public static int warmupIterations() {
        return Integer.getInteger("ruslo.perf.warmups", 1);
    }

    /** Returns the number of measured iterations from a system property. */
    public static int measuredIterations() {
        return Integer.getInteger("ruslo.perf.iterations", 3);
    }
}
