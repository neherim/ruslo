# Обработка ошибок

[English](../error-handling.md) | Русский

Исключение из шага, обработчика сообщения или таймера означает технический сбой или ошибку программирования.
Ожидаемую бизнес-ситуацию (отклонённая карта, отказ по заявке) выражайте результатом ветвящегося обработчика.

Исключение откатывает транзакцию обработчика: состояние, переменные и обрабатывавшееся событие остаются как были.
Затем движок вызывает `ProcessErrorHandler` процесса в отдельной транзакции, и его результат решает, что будет дальше.

## Политика ошибок

```java
.onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(5)))
.onGiveUp(CANCELLING)
.initialState(CREATED)
// ...
.step(CANCELLING, cancelOrderStep, CANCELLED)
.terminal(CANCELLED)
```

Оба вызова необязательны: `onError` задаёт обработчик (по умолчанию `noRetry()`), `onGiveUp` — состояние, в которое
процесс переходит, когда обработчик сдаётся.

Обработчик возвращает один из трёх результатов:

| Результат | Что происходит |
|---|---|
| `ErrorResult.retryAfter(delay)` | Шаг или ожидание выполняется снова через `delay` |
| `ErrorResult.giveUp()` | Процесс переходит в состояние `onGiveUp`; без `onGiveUp` останавливается в `FAILED` |
| `ErrorResult.fail()` | Процесс останавливается в `FAILED` в текущем состоянии, объявлен `onGiveUp` или нет |

Встроенные обработчики:

| Обработчик | Поведение |
|---|---|
| `ProcessErrorHandler.noRetry()` | `giveUp()` при первой ошибке |
| `ProcessErrorHandler.fixed(maxRetries, delay)` | До `maxRetries` повторов с фиксированной задержкой, затем `giveUp()` |
| `ProcessErrorHandler.exponential(maxRetries, initialDelay, maxDelay)` | То же с удваивающейся задержкой |

Свой обработчик получает `ProcessContext` и `ProcessError` с исключением (`cause`) и числом предыдущих неудачных
попыток в текущем состоянии (`attempt`, `0` для первой ошибки):

```java
(context, error) -> {
    if (error.cause() instanceof PermanentlyUnavailableException) return ErrorResult.giveUp();
    if (error.cause() instanceof DataCorruptionException) return ErrorResult.fail();
    if (error.attempt() < 3) return ErrorResult.retryAfter(Duration.ofSeconds(10));
    return ErrorResult.giveUp();
}
```

## Состояние отказа

`giveUp()` — обычный переход в состояние `onGiveUp`, например в шаг компенсации. Чтобы передать причину, обработчик
ошибок записывает переменную в `ProcessContext`, а состояние отказа её читает.

Ошибка в самом состоянии `onGiveUp` останавливает процесс в `FAILED`, чтобы падающая компенсация не зациклилась.
Косвенные циклы (`CANCELLING → X`, ошибка в `X` → `CANCELLING`) движок не обнаруживает.

## Повторы в ожидании

Повтор приостанавливает всё ожидание: до повтора новые сообщения и наступившие таймеры не обрабатываются. Если у
ожидания жёсткий срок, ограничьте повторы или перехватите ошибку в обработчике. Подробнее —
[execution.md](execution.md#повтор-приостанавливает-ожидание).

## Процессы в FAILED

Процесс в `FAILED` не выполняется: ни шаги, ни таймеры. Найдите такие процессы по статусу
([operations.md](operations.md#поиск-процессов)), устраните причину и продолжите через
`processEngine.resumeFailedProcess(id)`: упавший шаг или событие выполняется снова, а счёт попыток начинается
заново. Для процесса не в `FAILED` `resumeFailedProcess` бросает `ProcessStatusException`.

## Подробности

- Изменения `ProcessContext`, сделанные обработчиком ошибок, сохраняются атомарно с его результатом.
- Если обработчик ошибок сам бросил исключение или вернул `null`, упавшее событие повторяется через 60 секунд.
- Ветвящийся обработчик, вернувший `null`, падает с `IllegalStateException`, которое обрабатывает та же политика.
- Успешный обработчик сбрасывает `attempt` и `lastError`.
- `lastError` хранит `SimpleClassName: message`, обрезанный до 1024 символов. Stack trace не хранится: ищите его в
  логе по id процесса.
