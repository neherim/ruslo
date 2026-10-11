package io.github.neherim.ruslo.perf.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Statistics for benchmark reports; no pass/fail thresholds. */
public final class BenchmarkStatistics {
    private BenchmarkStatistics() {
    }

    /** Returns the given percentile of nanosecond samples in milliseconds. */
    public static double percentileMillis(List<Long> nanos, double percentile) {
        if (nanos.isEmpty()) return 0.0;
        var sorted = new ArrayList<>(nanos);
        sorted.sort(Long::compareTo);
        var index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index) / 1_000_000.0;
    }

    /** Returns the p50/p95/p99 latency summary in milliseconds. */
    public static Map<String, Object> latencyMillis(List<Long> nanos) {
        var result = new LinkedHashMap<String, Object>();
        result.put("p50", percentileMillis(nanos, 0.50));
        result.put("p95", percentileMillis(nanos, 0.95));
        result.put("p99", percentileMillis(nanos, 0.99));
        result.put("max", percentileMillis(nanos, 1.0));
        return result;
    }

    /** Returns throughput min/median/max and absolute spread. */
    public static Map<String, Object> throughputSummary(List<Double> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        var result = new LinkedHashMap<String, Object>();
        result.put("min", sorted.getFirst());
        result.put("median", median(sorted));
        result.put("max", sorted.getLast());
        result.put("spread", sorted.getLast() - sorted.getFirst());
        return result;
    }

    /** Returns the median of a non-empty sample. */
    public static double median(List<Double> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compareTo);
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 0
                ? sorted.get(middle - 1) / 2 + sorted.get(middle) / 2
                : sorted.get(middle);
    }
}
