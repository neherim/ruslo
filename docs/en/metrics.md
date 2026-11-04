# Metrics

The Spring Boot starter registers Micrometer metrics when a `MeterRegistry` is present. Without Spring, pass a
`ProcessEngineMetrics` implementation to the builder.

## Processes and steps

| Metric | Type | Meaning |
|---|---|---|
| `ruslo.processes.started` | Counter, `type` | Process starts |
| `ruslo.processes.completed` | Counter, `type` | Completed processes |
| `ruslo.processes.failed` | Counter, `type` | Processes that moved to status `FAILED` |
| `ruslo.processes.steps.duration` | Timer, `type`, `state`, `outcome` | Duration of process steps. `outcome=success`: the handler completed successfully, `outcome=error`: it threw an exception |
| `ruslo.processes` | Gauge, `status` | Number of processes in statuses `ACTIVE` and `FAILED` |
| `ruslo.system.errors` | Counter | Errors of the engine itself, not related to your handlers (usually database unavailability) |

A metric is tagged with the state whose handler was called. Errors are visible through `outcome=error`, stops through
`processes.failed`. There is no separate error counter: the `count` of `ruslo.processes.steps.duration` with
`outcome=error` is the number of handler errors.

## Workers

| Metric | Type | Meaning |
|---|---|---|
| `ruslo.worker.capacity` | Gauge, `worker` | Maximum number of steps running at the same time (`max-concurrency`) |
| `ruslo.worker.active` | Gauge, `worker` | Steps running now on this node |
| `ruslo.worker.ready` | Gauge, `worker` | The worker's processes that are ready to run but not yet taken by any node |
| `ruslo.worker.lag` | TimeGauge, `worker` | How long the oldest of them has been waiting; zero if there are none |
| `ruslo.lease.lost` | Counter | Step results discarded because the node lost ownership of the process (e.g. after a node pause longer than `instance-timeout`); such a step runs again |

`worker.lag` is the main stall signal: it grows if the dispatcher has stopped or capacity is short. `worker.ready`
and `worker.lag` are cluster-wide values from the database, so every instance shows the same value.

## Gauge cost

The `processes`, `worker.ready` and `worker.lag` gauges query the database on every registry read (e.g. on a
Prometheus scrape) on every instance: two queries for `processes` and two for each worker. The queries use indexes
and do not read completed processes.

## Custom implementation

The extension contract is `io.github.neherim.ruslo.core.spi.ProcessEngineMetrics`.
All callbacks have a no-op default implementation; override only the metrics you need.
Exceptions in callbacks are caught and do not break process execution. Keep strong references to the suppliers passed
to `register*`: the engine does not hold them.
