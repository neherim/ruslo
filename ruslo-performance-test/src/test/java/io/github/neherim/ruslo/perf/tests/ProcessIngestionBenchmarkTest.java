package io.github.neherim.ruslo.perf.tests;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.metrics.InMemoryProcessEngineMetrics;
import io.github.neherim.ruslo.perf.config.BenchmarkEnvironment;
import io.github.neherim.ruslo.perf.config.BenchmarkPostgres;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Measures the public start() without competing process execution. */
@Tag("performance")
@Testcontainers(disabledWithoutDocker = false)
class ProcessIngestionBenchmarkTest {
    private static final Logger log = LoggerFactory.getLogger(ProcessIngestionBenchmarkTest.class);
    private static final ProcessType TYPE = ProcessType.of("PERF_INGESTION");
    private static final int PROCESS_COUNT = BenchmarkRunner.processCount();
    private static final int WARMUP_COUNT = Math.min(1_000, PROCESS_COUNT);

    @Container
    static final PostgreSQLContainer POSTGRES = BenchmarkPostgres.container();

    @Test
    void benchmarkProcessIngestion() {
        try (var environment = new BenchmarkEnvironment(POSTGRES)) {
            var configurations = new ArrayList<ConfigurationResult>();
            for (var threads : List.of(1, 8)) {
                var iterations = BenchmarkRunner.run(index -> runIteration(
                        environment, threads, index < 0 ? WARMUP_COUNT : PROCESS_COUNT));
                configurations.add(new ConfigurationResult(threads, iterations,
                        BenchmarkStatistics.throughputSummary(iterations.stream()
                                .map(IterationResult::startsPerSecond).toList())));
            }
            BenchmarkReportWriter.write("process-ingestion", environment, Map.of(
                    "processCount", PROCESS_COUNT, "warmupProcessCount", WARMUP_COUNT,
                    "warmupIterations", BenchmarkRunner.warmupIterations(),
                    "measuredIterations", BenchmarkRunner.measuredIterations(),
                    "producerThreads", List.of(1, 8), "poolSizeByProducerThreads", Map.of("1", 3, "8", 10),
                    "dispatcherEnabled", false, "historyEnabled", true), configurations, log);
        }
    }

    private IterationResult runIteration(BenchmarkEnvironment environment, int threads, int count) {
        environment.truncateEngineTables();
        ProcessDefinition definition = builder -> builder.type(TYPE).initialState(State.READY)
                .step(State.READY, context -> { }, State.DONE).terminal(State.DONE).build();
        var metrics = new InMemoryProcessEngineMetrics();
        var engine = environment.createEngine("ingestion-" + threads, threads + 2, 1, false,
                metrics, List.of(definition), builder -> {});
        engine.start();
        var samples = new ArrayList<ProducerResult>();
        long durationNanos;
        try (var executor = Executors.newFixedThreadPool(threads)) {
            var ready = new CountDownLatch(threads);
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<ProducerResult>>();
            for (int thread = 0; thread < threads; thread++) {
                var producer = thread;
                futures.add(executor.submit(() -> produce(engine, count, producer, threads, ready, start)));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).as("producers ready").isTrue();
            var startedAt = System.nanoTime();
            start.countDown();
            for (var future : futures) samples.add(future.get(10, TimeUnit.MINUTES));
            durationNanos = samples.stream().mapToLong(ProducerResult::finishedAtNanos).max().orElseThrow() - startedAt;
        } catch (Exception ex) {
            throw new IllegalStateException("Process producer failed", ex);
        } finally {
            environment.stopEngine(engine);
        }
        var ids = samples.stream().flatMap(sample -> sample.ids().stream()).toList();
        var latencies = samples.stream().flatMap(sample -> sample.latencies().stream()).toList();
        assertThat(ids).hasSize(count).doesNotHaveDuplicates();
        assertThat(environment.database().queryForObject("select count(*) from xs_process_instance",
                rs -> rs.getLong(1)).orElseThrow()).isEqualTo(count);
        assertThat(environment.database().queryForObject("select count(*) from xs_process_state_hist",
                rs -> rs.getLong(1)).orElseThrow()).isEqualTo(count);
        assertThat(metrics.getTotalProcessesStarted()).isEqualTo(count);
        return new IterationResult(count, durationNanos / 1_000_000.0,
                count * 1_000_000_000.0 / durationNanos, BenchmarkStatistics.latencyMillis(latencies));
    }

    private static ProducerResult produce(ProcessEngine engine, int count, int producer, int threads,
                                          CountDownLatch ready, CountDownLatch start) throws InterruptedException {
        var ids = new ArrayList<UUID>();
        var latencies = new ArrayList<Long>();
        ready.countDown();
        start.await();
        for (int index = producer; index < count; index += threads) {
            var startedAt = System.nanoTime();
            var id = engine.newProcess(TYPE).start();
            var elapsed = System.nanoTime() - startedAt;
            ids.add(id);
            latencies.add(elapsed);
        }
        return new ProducerResult(ids, latencies, System.nanoTime());
    }

    private enum State implements ProcessState { READY, DONE }
    private record ProducerResult(List<UUID> ids, List<Long> latencies, long finishedAtNanos) {}
    private record ConfigurationResult(int producerThreads, List<IterationResult> iterations,
                                       Map<String, Object> throughput) {}
    private record IterationResult(int processCount, double durationMillis, double startsPerSecond,
                                   Map<String, Object> startLatencyMillis) {}
}
