# Metrics

The Spring Boot starter registers Micrometer metrics when a `MeterRegistry` is present. Without Spring, pass a
`ProcessEngineMetrics` implementation to the builder.

## Processes and steps

| Metric | Type | Meaning |
|---|---|---|
| `ruslo.processes.started` | Counter, `type` | Process starts, after commit |
| `ruslo.processes.completed` | Counter, `type` | Transitions to a terminal state, after commit |
| `ruslo.processes.failed` | Counter, `type` | Transitions to status `FAILED`, after commit |
| `ruslo.processes.steps.duration` | Timer, `type`, `state`, `outcome` | Duration of steps and message and timer handlers; `outcome=success`: the handler returned (even if saving the transition then failed), `outcome=error`: it threw an exception |
| `ruslo.processes` | Gauge, `status` | Number of processes in statuses `ACTIVE` and `FAILED`; the values do not overlap |
| `ruslo.system.errors` | Counter | Errors of the engine itself, not related to your handlers (usually database unavailability) |

A metric is tagged with the state whose handler was called. Retries and give-ups are not counted separately: errors
are visible through `outcome=error`, stops through `processes.failed`.

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
