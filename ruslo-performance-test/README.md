# Performance benchmarks

Two scenarios measure the overhead of creating processes through the public API and of executing
processes of five empty steps on PostgreSQL. Messages, timers and business logic are not part
of the load. PostgreSQL correctness tests live in core: see [testing](../docs/testing.md).

Benchmarks run only with the `perf-tests` profile. There are no pass/fail timing thresholds:
the result is checked for correctness, the performance is recorded for comparison.
Regular `test` and `verify` run only the fast checks of the statistics and the report generator.

## Requirements and running

Requires a running Docker and up to 4 GiB of memory for the forked JVM. Each benchmark class starts
its own `postgres:18.4-alpine`, shared by the warmups and measured iterations of the class.
Reusable containers, tmpfs and an external PostgreSQL are not used.
PostgreSQL durability is explicitly on: `fsync`, `synchronous_commit` and `full_page_writes` are `on`.
If Docker is unavailable, an explicitly activated profile fails.

```bash
./mvnw test -Pperf-tests -pl ruslo-performance-test -am \
  -Druslo.perf.environment="CPU, RAM, disk, Docker VM CPU/RAM and database placement"
```

To run one scenario add `-Dtest=ProcessIngestionBenchmarkTest` or
`-Dtest=ProcessExecutionBenchmarkTest`. Such a report is explicitly marked as incomplete.
In `perf-tests` upstream tests are skipped, but upstream modules are built thanks to `-am`.

Diagnostic run:

```bash
./mvnw test -Pperf-tests -pl ruslo-performance-test -am \
  -Druslo.perf.process-count=100 \
  -Druslo.perf.warmups=0 \
  -Druslo.perf.iterations=1
```

| Property | Default | Purpose |
|---|---:|---|
| `ruslo.perf.process-count` | 10 000 | Size of the measured batch, positive |
| `ruslo.perf.warmups` | 1 | Number of warmups per configuration, non-negative |
| `ruslo.perf.iterations` | 3 | Number of measured iterations per configuration, positive |
| `ruslo.perf.environment` | `not provided` | Verified description of the hardware and Docker VM for the report |
| `ruslo.perf.output-directory` | `target/performance-results/<timestamp>` | Separate empty directory of the current run |

Each warmup iteration runs at most 1 000 processes. Diagnostic results are not
a baseline; a baseline needs the default load, at least one warmup and three measurements.

## Scenarios

**Ingestion (`ProcessIngestionBenchmarkTest`)**: public `newProcess(...).start()`,
1/8 producer threads, pool of 3/10 connections, dispatcher disabled, history enabled.
Latency is measured until return after commit. Throughput covers creation only, without execution.

**Execution (`ProcessExecutionBenchmarkTest`)**: five empty steps, each transition in its own
transaction, history enabled, TRANSACTIONAL/NON_TRANSACTIONAL modes, 1/2/4 engines.
Each engine has 16 worker slots and a pool of 18 connections, polling every 10 ms; the service pool has 2 connections.
All engines run in one JVM and share one PostgreSQL. As engines are added,
total concurrency grows 16/32/64 and total engine pools 18/36/72 connections.

Data is loaded by SQL in an uncommitted transaction, and the engines start before it commits.
The stopwatch starts right before the commit: loading and startup are excluded,
the load commit is included. Before the commit the engines do not see the backlog. Completion is detected
by metric notifications after commit; SQL checks run after the measurement.
Latency includes queueing of the whole backlog and execution, not only the five steps of a single process.

## Report

Each run creates its own directory:

```text
target/performance-results/<timestamp>/
  process-ingestion.json
  process-execution.json
  performance-report.md
```

After each successfully verified scenario the Markdown is updated automatically, with
data of the current run only. The report contains the environment, PostgreSQL settings, workload,
throughput median/min–max, latency p50/p95/p99, scaling and individual iterations.
Reported percentiles are medians of per-iteration percentiles, not of a merged sample.

Success of the whole command is confirmed by Maven, not by the presence of a report. Do not reuse
another run's directory: writing a scenario's JSON again is rejected.

A verified full baseline can be saved to `docs/performance/<date>-<commit>/`
by copying the Markdown and both JSON files there. The hardware and Docker VM limits must be
described; container limits do not replace Docker VM limits. Compare results
only under comparable load, settings and environment.

Rules for AI agents on producing and publishing the report: [AGENTS.md](AGENTS.md).
