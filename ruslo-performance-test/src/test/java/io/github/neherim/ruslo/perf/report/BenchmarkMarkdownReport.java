package io.github.neherim.ruslo.perf.report;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Locale;

/** Renders the user-facing Markdown report from the JSON results of one run. */
public final class BenchmarkMarkdownReport {
    private BenchmarkMarkdownReport() {}

    /** Renders measurement tables, the methodology and the limits of the results. */
    public static String render(List<JsonNode> reports) {
        var out = new StringBuilder("# Ruslo performance\n\n");
        var complete = reports.stream().anyMatch(report -> report.path("benchmark").asString().equals("process-ingestion"))
                && reports.stream().anyMatch(report -> report.path("benchmark").asString().equals("process-execution"));
        out.append(complete ? "Scenarios: both.\n\n" : "Scenarios: **incomplete run**, only one scenario is available.\n\n");
        out.append("The report contains only scenarios that passed their correctness checks. The Maven result confirms the success of the whole run.\n\n")
                .append("Empty handlers measure engine and PostgreSQL overhead. Business logic, HTTP, messages and timers are not part of the workload.\n\n")
                .append("The numbers apply to the stated environment and workload; they do not guarantee application throughput or latency.\n\n");
        var diagnostic = reports.stream().anyMatch(report ->
                report.path("workload").path("processCount").asInt() < 10_000
                || report.path("workload").path("warmupIterations").asInt() < 1
                || report.path("workload").path("measuredIterations").asInt() < 3);
        if (diagnostic) out.append("**Diagnostic run: the workload or iteration count is below the baseline requirements.**\n\n");
        for (var report : reports) {
            var ingestion = report.path("benchmark").asString().equals("process-ingestion");
            out.append(ingestion ? "## Process creation\n\n" : "## Execution and scaling\n\n");
            out.append("Result time (UTC): ").append(cell(report.path("timestampUtc"))).append(".\n\n");
            if (ingestion) {
                out.append("\nDispatcher disabled, history enabled. 1/8 threads call `newProcess(...).start()` sequentially; pool of 3/10 connections.\n\n")
                        .append("Latency: from the public API call until it returns after the process creation is committed. Throughput: the whole batch, from releasing the ready producers until the last producer finishes.\n\n")
                        .append("### Summary\n\n| Producers | Median, creations/s | Min–max | p50, ms | p95, ms | p99, ms |\n|---:|---:|---:|---:|---:|---:|\n");
                for (var configuration : report.path("iterations")) {
                    summary(out, configuration.path("producerThreads").asString(), configuration,
                            "startLatencyMillis", "");
                }
            } else {
                out.append("\nThe process batch is preloaded with SQL in an uncommitted transaction. Engines start before its commit; the stopwatch starts right before the commit. Loading and engine startup are excluded, the load commit is included.\n\n")
                        .append("Each process runs five empty steps. Every transition is committed separately; history enabled. Latency: from the start of the measurement until the process completion notification after commit, including queueing behind the whole backlog. This is not the latency of a single request under light load.\n\n")
                        .append("All engine instances run **in one JVM** against one PostgreSQL. Each engine has 16 worker slots and a pool of 18 connections; polling 10 ms. Adding engines raises total concurrency to 16/32/64 and connections to 18/36/72. The admin pool adds 2 connections. This does not measure multiple servers.\n\n")
                        .append("### Summary\n\n| Mode / engines | Median, processes/s | Min–max | p50, ms | p95, ms | p99, ms | Median, steps/s | Speedup vs 1 engine |\n|---|---:|---:|---:|---:|---:|---:|---:|\n");
                for (var mode : report.path("iterations")) {
                    for (var topology : mode.path("topologies")) {
                        summary(out, mode.path("executionMode").asString() + " / " + topology.path("engineCount").asString(),
                                topology, "completionLatencyMillis", " | " + number(median(topology.path("iterations"), "stepsPerSecond"))
                                        + " | " + number(topology.path("speedupRelativeToOneEngine").asDouble()) + "×");
                    }
                }
            }
            environment(out, report.path("environment"));
            out.append("### Workload\n\n| Parameter | Value |\n|---|---|\n");
            for (var entry : report.path("workload").properties()) {
                out.append("| ").append(entry.getKey()).append(" | ").append(cell(entry.getValue())).append(" |\n");
            }
            out.append("\nIn the summary table p50/p95/p99 are medians of the per-iteration percentiles, **not percentiles of the pooled sample**. Warmup is excluded from all results.\n\n")
                    .append("### Measured iterations\n\n| Configuration | Iteration | Duration, ms | ")
                    .append(ingestion ? "Creations/s" : "Processes/s")
                    .append(" | p50, ms | p95, ms | p99, ms |\n|---|---:|---:|---:|---:|---:|---:|\n");
            for (var configuration : report.path("iterations")) {
                if (ingestion) {
                    iterations(out, configuration.path("producerThreads").asString() + " producers", configuration,
                            "startsPerSecond", "startLatencyMillis");
                } else {
                    for (var topology : configuration.path("topologies")) {
                        iterations(out, configuration.path("executionMode").asString() + " / " + topology.path("engineCount").asString(),
                                topology, "processesPerSecond", "completionLatencyMillis");
                    }
                }
            }
            var name = report.path("benchmark").asString() + ".json";
            out.append("\nRaw data: [").append(name).append("](").append(name).append(").\n\n");
        }
        out.append("## Reading and comparing\n\n")
                .append("- Creation and execution are measured separately: do not add their throughput or read it as the full start → completion path.\n")
                .append("- NON_TRANSACTIONAL keeps at-least-once semantics. Higher throughput does not make it equivalent to TRANSACTIONAL.\n")
                .append("- For a baseline use 10 000 processes, at least one warmup and three measured iterations. Smaller runs are diagnostic.\n")
                .append("- Compare only identical workload, commit/configuration, JVM, hardware, Docker VM resources, PostgreSQL and its settings. Zero container limits mean the container has no limit of its own, not that the Docker VM is unlimited.\n")
                .append("- If hardware or Docker VM resources are not described, complete the report before publishing. Keep the raw JSON next to the Markdown.\n");
        return out.toString();
    }

    private static void environment(StringBuilder out, JsonNode environment) {
        out.append("\n### Environment\n\n| Parameter | Value |\n|---|---|\n");
        for (var entry : environment.properties()) {
            if (entry.getKey().equals("postgres") || entry.getKey().equals("gitChanges")) continue;
            out.append("| ").append(entry.getKey()).append(" | ").append(cell(entry.getValue())).append(" |\n");
        }
        for (var entry : environment.path("postgres").properties()) {
            if (entry.getKey().equals("settings")) {
                for (var setting : entry.getValue()) {
                    out.append("| PostgreSQL: ").append(cell(setting.path("name"))).append(" | ")
                            .append(cell(setting.path("setting"))).append(' ').append(cell(setting.path("unit"))).append(" |\n");
                }
            } else {
                out.append("| PostgreSQL: ").append(entry.getKey()).append(" | ").append(cell(entry.getValue())).append(" |\n");
            }
        }
        out.append('\n');
    }

    private static void summary(StringBuilder out, String label, JsonNode configuration, String latencyField, String suffix) {
        var throughput = configuration.path("throughput");
        out.append("| ").append(label).append(" | ").append(number(throughput.path("median").asDouble()))
                .append(" | ").append(number(throughput.path("min").asDouble())).append("–")
                .append(number(throughput.path("max").asDouble()));
        for (var percentile : List.of("p50", "p95", "p99")) {
            var values = new java.util.ArrayList<Double>();
            for (var iteration : configuration.path("iterations")) values.add(iteration.path(latencyField).path(percentile).asDouble());
            out.append(" | ").append(number(BenchmarkStatistics.median(values)));
        }
        out.append(suffix).append(" |\n");
    }

    private static void iterations(StringBuilder out, String label, JsonNode configuration, String rate, String latency) {
        int index = 0;
        for (var iteration : configuration.path("iterations")) {
            out.append("| ").append(label).append(" | ").append(++index).append(" | ")
                    .append(number(iteration.path("durationMillis").asDouble())).append(" | ")
                    .append(number(iteration.path(rate).asDouble()));
            for (var percentile : List.of("p50", "p95", "p99")) {
                out.append(" | ").append(number(iteration.path(latency).path(percentile).asDouble()));
            }
            out.append(" |\n");
        }
    }

    private static double median(JsonNode iterations, String field) {
        var values = new java.util.ArrayList<Double>();
        for (var iteration : iterations) values.add(iteration.path(field).asDouble());
        return BenchmarkStatistics.median(values);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String cell(JsonNode value) {
        return (value.isString() ? value.asString() : value.toString())
                .replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }
}
