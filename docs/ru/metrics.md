# Метрики

[English](../metrics.md) | Русский

Spring Boot starter регистрирует метрики Micrometer при наличии `MeterRegistry`. При запуске без Spring можно передать реализацию `ProcessEngineMetrics` в builder.

## Процессы и шаги

| Метрика | Тип | Смысл |
|---|---|---|
| `ruslo.processes.started` | Counter, `type` | Запуски процессов после commit |
| `ruslo.processes.completed` | Counter, `type` | Переходы в terminal после commit |
| `ruslo.processes.failed` | Counter, `type` | Переходы в статус `FAILED` после commit |
| `ruslo.processes.steps.duration` | Timer, `type`, `state`, `outcome` | Длительность шагов и обработчиков сообщений и таймеров; `outcome=success` — обработчик вернул управление (даже если сохранение перехода затем не удалось), `outcome=error` — бросил исключение |
| `ruslo.processes` | Gauge, `status` | Число процессов в статусах `ACTIVE` и `FAILED`; значения не пересекаются |
| `ruslo.system.errors` | Counter | Ошибки самого движка, не связанные с вашими обработчиками (обычно недоступность БД) |

Метрика привязывается к состоянию, обработчик которого был вызван. Retry и fallback отдельно не считаются: число ошибок видно по `outcome=error`, остановки — по `processes.failed`.

## Workers

| Метрика | Тип | Смысл |
|---|---|---|
| `ruslo.worker.capacity` | Gauge, `worker` | Максимум одновременно исполняемых шагов (`max-concurrency`) |
| `ruslo.worker.active` | Gauge, `worker` | Исполняемые сейчас шаги на этом узле |
| `ruslo.worker.ready` | Gauge, `worker` | Процессы worker'а, готовые к исполнению, но ещё не взятые ни одним узлом |
| `ruslo.worker.lag` | TimeGauge, `worker` | Сколько ждёт исполнения самый старый из них; ноль, если таких нет |
| `ruslo.lease.lost` | Counter | Результаты шагов, отброшенные из-за потери владения процессом (например, после паузы узла дольше `instance-timeout`); такой шаг будет выполнен повторно |

`worker.lag` — основной сигнал застоя: он растёт, если диспетчер остановился или ёмкости не хватает. `worker.ready` и `worker.lag` — общие для кластера значения из БД, поэтому каждый экземпляр показывает одно и то же.

## Стоимость gauges

Gauges `processes`, `worker.ready` и `worker.lag` выполняют запрос к БД при каждом чтении registry (например, при scrape Prometheus) на каждом экземпляре: два запроса на `processes` и по два на каждый worker. Запросы используют индексы и не читают завершённые процессы.

## Собственная реализация

Контракт расширения находится в `io.github.neherim.ruslo.core.spi.ProcessEngineMetrics`.
Все callbacks имеют default-реализацию без действий; можно переопределить только нужные метрики.
Исключения в callbacks перехватываются и не нарушают выполнение процессов. Suppliers, переданные в `register*`,
храните по сильной ссылке: движок не держит их у себя.
