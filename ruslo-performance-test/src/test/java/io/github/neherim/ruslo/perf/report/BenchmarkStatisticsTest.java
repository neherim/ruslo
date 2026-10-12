package io.github.neherim.ruslo.perf.report;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkStatisticsTest {
    @Test
    void averagesCentralValuesForEvenSampleAndSelectsMiddleForOddSample() {
        assertThat(BenchmarkStatistics.median(List.of(400.0, 100.0, 200.0, 300.0))).isEqualTo(250.0);
        assertThat(BenchmarkStatistics.throughputSummary(List.of(400.0, 100.0, 200.0, 300.0)))
            .containsEntry("median", 250.0)
            .containsEntry("min", 100.0)
            .containsEntry("max", 400.0);
        assertThat(BenchmarkStatistics.median(List.of(30.0, 10.0, 20.0))).isEqualTo(20.0);
    }

    @Test
    void usesNearestRankAndConvertsNanosecondsToMilliseconds() {
        assertThat(BenchmarkStatistics.latencyMillis(List.of(3_000_000L, 1_000_000L, 2_000_000L)))
            .containsEntry("p50", 2.0)
            .containsEntry("p95", 3.0)
            .containsEntry("p99", 3.0);
    }
}
