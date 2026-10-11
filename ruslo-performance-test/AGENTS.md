# Agent instructions: benchmarks and performance-report.md

## Scope

The module answers two user questions: how many processes per second can be created, and how many processes of five
empty steps per second can be executed. Messages, timers, recovery, SQL microbenchmarks and PostgreSQL correctness
checks are out of scope; PostgreSQL integration tests live in `ruslo-core` (profile `postgresql-tests`).
Do not add scenarios or load parameters without a real user need.

## Running

```bash
./mvnw test -Pperf-tests -pl ruslo-performance-test -am \
  -Druslo.perf.environment="<hardware, RAM, disk, Docker VM CPU/RAM, DB placement>"
```

Replace the placeholder with verified data.

- Baseline: 10 000 processes, at least one warmup and three measured iterations (the defaults).
- Diagnostic run: `ruslo.perf.process-count=100`, `ruslo.perf.warmups=0`, `ruslo.perf.iterations=1`.
  Always call it diagnostic, never a baseline.
- Record the commit and working tree state. Prefer a clean checkout. If uncommitted changes were measured, list them,
  save the diff and do not attribute the result to the clean commit.
- Do not run other benchmarks in parallel. Docker is required; tests fail rather than skip without it.
- Do not weaken PostgreSQL durability.

## Results and report generation

Each Maven run writes to its own directory:

```text
target/performance-results/<timestamp>/
  process-ingestion.json
  process-execution.json
  performance-report.md
```

`BenchmarkReportWriter` saves the JSON of each verified scenario and regenerates the Markdown via
`BenchmarkMarkdownReport`. The directory is shared by forked JVMs of one run; never mix runs in it. If you override
`ruslo.perf.output-directory`, use a new empty path: writing a scenario's JSON twice is an error.

- Success is the Maven and Surefire exit status, not the presence of the Markdown or JSON files.
- A selective run (`-Dtest=...`) produces an incomplete report. Do not fill it with results from older runs and do
  not hide the missing scenario.
- Never invent results. Compute every number from the JSON of the current run.
- The generated Markdown is a technical draft. Before publishing, write a compact `performance-report.md` from the
  same JSON per the rules below, in the publication directory. Do not edit generated files in `target/`.
- If you change the generated template, align `BenchmarkMarkdownReport` and its tests with these rules.
  Do not change measurements to improve the report.

## Content of the published performance-report.md

The reader is a library user, not a benchmark maintainer. In one minute they should understand the order of
throughput, the scaling limits and whether the numbers apply to their application. Target: up to 400 words,
two small tables, no technical appendix (the JSON is the appendix).

Sections, in order:

1. **Summary**: 2–3 sentences with process creations and executions per second and the actual gain from 2/4 engines.
   Start with numbers and what they mean, not with JVM settings or methodology. Mark a diagnostic or incomplete run
   up front; never present diagnostic numbers as expected performance.
2. **Process creation**: a table with rows for 1/8 threads: median creations/s and min–max. Optionally p95 creation
   time in ms; no full percentile set.
3. **Execution**: a table for both modes and 1/2/4 engines: median processes/s, min–max and speedup over one engine
   in the same mode. State once that a process has five empty steps, so steps/s = processes/s × 5.
4. **What limits throughput**: a short paragraph on PostgreSQL and scaling (see below), with the speedups of this run.
5. **Conditions**: one short paragraph: CPU/RAM, PostgreSQL version, load size, number of measurements; all engines
   in one JVM sharing one database, history and durable commits enabled, empty handlers. Date, commit and dirty tree
   marker on one line. If the hardware is unknown, say so briefly.
6. **Raw data**: relative links to both JSON files (or the one available in an incomplete run).

Leave out Java/JVM/GC/heap/OS tables, Docker/container limits, PostgreSQL settings, connection pools, polling,
per-iteration rows, SQL preload, commit order and metric collection internals. They stay in the JSON, README and
these instructions. Do not repeat the environment per scenario; name significant differences between scenarios.

Numbers:

- Throughput: 2–3 significant digits. Speedup: one decimal place.
- Keep units and the min–max range. Never report only the best iteration.
- Median of an even count is the mean of the two middle values.
- A reported percentile is the median of per-iteration percentiles, not a percentile of the merged sample.
- Warmups are excluded.

## Database and scaling conclusion

State it plainly: the library commits every process transition to the database, so the shared PostgreSQL limits
the gain from adding engines; more engines does not mean linear growth. Commit and disk speed matter for this
workload.

- Back it with the measurements: show what 2 and 4 engines gave versus one, and where the gain shrinks, disappears or
  turns negative. If there is no gain, say so.
- Do not promise speedup from more library instances alone; the shared database's headroom comes first.
- Separate the architectural limit from a proven cause. Speedup ratios alone do not prove DB CPU or disk saturation;
  without dedicated diagnostics, do not attribute a plateau to a specific PostgreSQL resource.
- If the run shows notable growth, do not hide it to fit the expected conclusion. Draw no scaling conclusions from a
  diagnostic run.

## Interpretation

- Ingestion: dispatcher disabled, history enabled; 1/8 producer threads, pool of 3/10 connections. Latency is public
  `newProcess(...).start()` until return after commit. Throughput covers the batch from producer release until the
  last producer finishes.
- Execution: SQL preload is excluded from timing, its commit is included. Engines cannot see processes until the
  preload commits. The stopwatch starts right before that commit and stops at the last completion notification.
  In-memory metric and timestamp collection is inside the measurement; heavy SQL checks run after it.
- Execution latency includes waiting in the shared backlog plus five steps; it is not single-request latency under
  light load. There is no end-to-end ingestion-to-execution measurement.
- 1/2/4 engines run in one JVM against one database; total concurrency and connections grow with the engine count.
  Do not call this multi-server scaling.
- Empty handlers measure engine overhead. Do not promise the same throughput to an application.
- TRANSACTIONAL and NON_TRANSACTIONAL differ in guarantees; do not recommend a mode by speed alone.
- Compare versions only under comparable load and environment, and name every difference.

## Publishing

After a successful full run, copy the whole result directory to
`docs/performance/<date>-<run number>-<short commit>/` so the Markdown and both JSON files stay together and relative
links work. In the copy, replace the generated Markdown with the compact version. Keep the original in `target/`.

- Do not overwrite a previous baseline unless asked. Do not commit `target/`.
- With a dirty working tree, save the diff next to the report.
- Never publish a diagnostic run as a baseline.
- If the run failed, report the reason; never produce a plausible example with numbers instead.
