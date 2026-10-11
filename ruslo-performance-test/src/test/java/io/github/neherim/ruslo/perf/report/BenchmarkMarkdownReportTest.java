package io.github.neherim.ruslo.perf.report;

import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkMarkdownReportTest {
    @Test
    void marksPartialRunAndEscapesEnvironmentTable() {
        var report = BenchmarkMarkdownReport.render(List.of(ingestion()));
        assertThat(report).contains("Diagnostic run", "incomplete run", "machine \\| notes more", "process-ingestion.json")
                .doesNotContain("## Execution and scaling");
    }

    @Test
    void rendersBothScenariosWithCorrectRatesAndMedianPercentiles() {
        var report = BenchmarkMarkdownReport.render(List.of(ingestion(), execution()));
        assertThat(report).contains("Scenarios: both.", "| 8 | 150.00 | 100.00–200.00 | 15.00 | 30.00 | 45.00 |",
                "| TRANSACTIONAL / 2 | 150.00 | 100.00–200.00 | 15.00 | 30.00 | 45.00 | 750.00 | 1.50× |",
                "| 8 producers | 1 | 1000.00 | 100.00 | 10.00 | 20.00 | 30.00 |",
                "| 8 producers | 2 | 500.00 | 200.00 | 20.00 | 40.00 | 60.00 |",
                "not percentiles of the pooled sample", "in one JVM", "process-execution.json");
    }

    @Test
    void doesNotLabelBaselineWorkloadAsDiagnostic() {
        var report = ingestion(Map.of("processCount", 10_000, "warmupIterations", 1, "measuredIterations", 3));
        assertThat(BenchmarkMarkdownReport.render(List.of(report))).doesNotContain("Diagnostic run");
    }

    private static JsonNode ingestion() {
        return ingestion(Map.of("processCount", 100));
    }

    private static JsonNode ingestion(Map<String, Object> workload) {
        return JsonUtils.DEFAULT_OBJECT_MAPPER.valueToTree(Map.of(
                "benchmark", "process-ingestion", "timestampUtc", "2026-09-30T10:00:00Z",
                "environment", Map.of("hardwareAndDockerNotes", "machine | notes\nmore"),
                "workload", workload,
                "iterations", List.of(Map.of("producerThreads", 8, "throughput", throughput(),
                        "iterations", List.of(iteration(100, "startLatencyMillis", 10),
                                iteration(200, "startLatencyMillis", 20))))));
    }

    private static JsonNode execution() {
        return JsonUtils.DEFAULT_OBJECT_MAPPER.valueToTree(Map.of(
                "benchmark", "process-execution", "environment", Map.of(), "workload", Map.of(),
                "iterations", List.of(Map.of("executionMode", "TRANSACTIONAL", "topologies", List.of(
                        Map.of("engineCount", 2, "speedupRelativeToOneEngine", 1.5,
                                "throughput", throughput(), "iterations", List.of(
                                        iteration(100, "completionLatencyMillis", 10),
                                        iteration(200, "completionLatencyMillis", 20))))))));
    }

    private static Map<String, Object> iteration(double rate, String latencyField, double p50) {
        return Map.of("durationMillis", 100_000 / rate, "startsPerSecond", rate, "processesPerSecond", rate,
                "stepsPerSecond", rate * 5, latencyField, Map.of("p50", p50, "p95", p50 * 2, "p99", p50 * 3));
    }

    private static Map<String, Object> throughput() {
        return BenchmarkStatistics.throughputSummary(List.of(100.0, 200.0));
    }
}
