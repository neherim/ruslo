# Performance report

## Summary

One engine creates about 2 350 processes/s from one thread and 5 900/s from eight. It executes about 960 processes/s
(4 800 steps/s) with the transactional worker and 770 processes/s with the non-transactional one. Two and four
engines on the same database add only 14–28%: the shared PostgreSQL, not the engine count, sets the limit.

## Process creation

`newProcess(...).start()` until return after commit; dispatcher disabled.

| Threads | Median, processes/s | Min–max | p95, ms |
|---:|---:|---:|---:|
| 1 | 2 350 | 2 270–2 440 | 0.47 |
| 8 | 5 940 | 5 300–5 980 | 1.7 |

## Execution

A process has five empty steps, each committed in its own transaction; steps/s = processes/s × 5.

| Mode | Engines | Median, processes/s | Min–max | Speedup |
|---|---:|---:|---:|---:|
| TRANSACTIONAL | 1 | 959 | 891–1 000 | 1.0 |
| TRANSACTIONAL | 2 | 1 130 | 1 090–1 130 | 1.2 |
| TRANSACTIONAL | 4 | 1 230 | 1 220–1 250 | 1.3 |
| NON_TRANSACTIONAL | 1 | 774 | 768–781 | 1.0 |
| NON_TRANSACTIONAL | 2 | 883 | 881–886 | 1.1 |
| NON_TRANSACTIONAL | 4 | 930 | 929–942 | 1.2 |

## What limits throughput

The engine commits every process transition to the database, so adding engines does not scale linearly when they
share one PostgreSQL. Here, doubling engines gave ×1.2 (transactional) and ×1.1 (non-transactional); going from two
to four added only another 9% and 5%. Before adding instances, check the database's headroom, including commit and
disk speed. This run does not show which PostgreSQL resource is saturated.

The two modes differ in guarantees; choose by guarantees, not by these numbers.

## Conditions

Apple M2 (8 cores), 16 GB RAM, internal SSD; Docker Desktop VM with 8 CPU / 7.75 GiB; PostgreSQL 18.4 in Docker on
the same machine. 10 000 processes per measurement, one warmup and three measured iterations per configuration.
All engines run in one JVM against one database; history and durable commits (`fsync`, `synchronous_commit`) are on;
handlers are empty, so the numbers measure engine overhead, not application throughput.

2026-10-11, commit `d5c2eb6`, clean working tree.

## Raw data

- [process-ingestion.json](process-ingestion.json)
- [process-execution.json](process-execution.json)
