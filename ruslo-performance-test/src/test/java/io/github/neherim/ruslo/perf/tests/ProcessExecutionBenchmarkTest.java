package io.github.neherim.ruslo.perf.tests;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.WorkerSpec.ExecutionMode;
import io.github.neherim.ruslo.core.metrics.InMemoryProcessEngineMetrics;
import io.github.neherim.ruslo.perf.config.BenchmarkEnvironment;
import io.github.neherim.ruslo.perf.config.BenchmarkPostgres;
import io.github.neherim.ruslo.perf.definition.FastProcessDefinition;
import io.github.neherim.ruslo.perf.report.BenchmarkReportWriter;
import io.github.neherim.ruslo.perf.report.BenchmarkStatistics;
import io.github.neherim.ruslo.perf.runner.BenchmarkRunner;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** Measures execution of five empty steps, each transition committed, and scaling across engines. */
@Tag("performance")
@Testcontainers(disabledWithoutDocker = false)
class ProcessExecutionBenchmarkTest {
    private static final Logger log = LoggerFactory.getLogger(ProcessExecutionBenchmarkTest.class);
    private static final int PROCESS_COUNT = BenchmarkRunner.processCount();
    private static final int WARMUP_COUNT = Math.min(1_000, PROCESS_COUNT);
    private static final int CONCURRENCY = 16;
    private static final int POOL_SIZE = 18;
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    @Container
    static final PostgreSQLContainer POSTGRES = BenchmarkPostgres.container();

    @Test
    void benchmarkExecutionThroughput() {
        try (var environment = new BenchmarkEnvironment(POSTGRES)) {
            var modes = new ArrayList<ModeResult>();
            for (var mode : ExecutionMode.values()) {
                var topologies = new ArrayList<TopologyResult>();
                for (var engines : List.of(1, 2, 4)) {
                    var iterations = BenchmarkRunner.run(index -> runIteration(
                            environment, mode, engines, index < 0 ? WARMUP_COUNT : PROCESS_COUNT));
                    topologies.add(new TopologyResult(engines, iterations,
                            BenchmarkStatistics.throughputSummary(iterations.stream()
                                    .map(IterationResult::processesPerSecond).toList()), 1.0));
                }
                var baseline = medianThroughput(topologies.getFirst());
                modes.add(new ModeResult(mode.name(), topologies.stream()
                        .map(result -> new TopologyResult(result.engineCount(), result.iterations(),
                                result.throughput(), medianThroughput(result) / baseline)).toList()));
            }
            BenchmarkReportWriter.write("process-execution", environment, Map.of(
                    "processCount", PROCESS_COUNT, "warmupProcessCount", WARMUP_COUNT,
                    "stepsPerProcess", 5, "engineCounts", List.of(1, 2, 4),
                    "workerConcurrencyPerEngine", CONCURRENCY, "poolSizePerEngine", POOL_SIZE,
                    "dispatchIntervalMillis", 10, "historyEnabled", true,
                    "warmupIterations", BenchmarkRunner.warmupIterations(),
                    "measuredIterations", BenchmarkRunner.measuredIterations()), modes, log);
        }
    }

    private IterationResult runIteration(BenchmarkEnvironment environment, ExecutionMode mode, int engineCount, int count) {
        environment.truncateEngineTables();
        var definition = new FastProcessDefinition(mode);
        var engines = new ArrayList<ProcessEngine>();
        var metrics = new ArrayList<CompletionMetrics>();
        for (int index = 0; index < engineCount; index++) {
            var metric = new CompletionMetrics();
            metrics.add(metric);
            engines.add(environment.createEngine("execution-" + mode + "-" + index,
                    POOL_SIZE, CONCURRENCY, true, metric, List.of(definition), builder -> {
                        if (mode == ExecutionMode.NON_TRANSACTIONAL) {
                            builder.registerWorker(WorkerSpec.builder(new WorkerId("perf-non-transactional"))
                                    .nonTransactional().maxConcurrency(CONCURRENCY).build());
                        }
                    }));
        }
        long startedAt;
        List<Long> completionTimes;
        try {
            // The uncommitted backlog is invisible to the engines. Measurement starts before the commit.
            startedAt = environment.database().doInTransactionWithResult(() -> {
                insertReadyProcesses(environment, definition, mode, count);
                engines.forEach(ProcessEngine::start);
                assertThat(metrics.stream().mapToLong(CompletionMetrics::getTotalProcessesCompleted).sum()).isZero();
                return System.nanoTime();
            });
            waitForCompletion(metrics, count);
            completionTimes = metrics.stream().flatMap(metric -> metric.completionTimes.stream()).toList();
        } finally {
            // If stopping one engine fails, the environment closes the remaining engines and pools.
            engines.forEach(environment::stopEngine);
        }
        assertThat(completionTimes).hasSize(count).allMatch(time -> time >= startedAt);
        var durationNanos = completionTimes.stream().mapToLong(Long::longValue).max().orElseThrow() - startedAt;
        var steps = metrics.stream().mapToLong(CompletionMetrics::getTotalStepsExecuted).sum();
        assertThat(steps).isEqualTo((long) count * 5);
        assertThat(scalar(environment, "select count(*) from xs_process_instance where status = 'COMPLETED'")).isEqualTo(count);
        assertThat(scalar(environment, "select count(*) from xs_process_instance where lease_owner is not null")).isZero();
        // The SQL load skips the initial history record, leaving five transitions.
        assertThat(scalar(environment, "select count(*) from xs_process_state_hist")).isEqualTo((long) count * 5);
        return new IterationResult(count, durationNanos / 1_000_000.0,
                count * 1_000_000_000.0 / durationNanos, steps * 1_000_000_000.0 / durationNanos,
                BenchmarkStatistics.latencyMillis(completionTimes.stream().map(time -> time - startedAt).toList()),
                metrics.stream().map(CompletionMetrics::getTotalProcessesCompleted).toList());
    }

    private static void insertReadyProcesses(BenchmarkEnvironment environment, FastProcessDefinition definition,
                                            ExecutionMode mode, int count) {
        environment.database().update("""
                insert into xs_process_instance
                    (id, create_at, state, worker_id, status, type, attempt, scheduled_at, available_at, messages, row_version)
                select md5(? || '-' || value)::uuid, current_timestamp, ?, ?, 'ACTIVE', ?, 0, current_timestamp, current_timestamp, '[]', 0
                from generate_series(1, ?) value
                """, mode.name(), FastProcessDefinition.State.STEP_1.name(),
                mode == ExecutionMode.TRANSACTIONAL ? WorkerId.DEFAULT.name() : "perf-non-transactional",
                definition.type().name(), count);
    }

    private static void waitForCompletion(List<CompletionMetrics> metrics, int expected) {
        var deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (metrics.stream().mapToLong(CompletionMetrics::getTotalProcessesCompleted).sum() == expected) return;
            LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
        }
        throw new AssertionError("Timeout waiting for " + expected + " completed processes");
    }

    private static long scalar(BenchmarkEnvironment environment, String sql) {
        return environment.database().queryForObject(sql, rs -> rs.getLong(1)).orElseThrow();
    }

    private static double medianThroughput(TopologyResult topology) {
        return BenchmarkStatistics.median(topology.iterations().stream().map(IterationResult::processesPerSecond).toList());
    }

    private static final class CompletionMetrics extends InMemoryProcessEngineMetrics {
        private final List<Long> completionTimes = Collections.synchronizedList(new ArrayList<>());

        /** Records the monotonic time of the completion notification after commit. */
        @Override
        public void recordProcessCompleted(ProcessType type) {
            completionTimes.add(System.nanoTime());
            super.recordProcessCompleted(type);
        }
    }

    private record ModeResult(String executionMode, List<TopologyResult> topologies) {}
    private record TopologyResult(int engineCount, List<IterationResult> iterations,
                                  Map<String, Object> throughput, double speedupRelativeToOneEngine) {}
    private record IterationResult(int processCount, double durationMillis, double processesPerSecond,
                                   double stepsPerSecond, Map<String, Object> completionLatencyMillis,
                                   List<Long> completedProcessesByEngine) {}
}
