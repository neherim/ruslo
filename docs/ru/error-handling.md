# Обработка ошибок

Исключение из шага, обработчика сообщения или таймера означает технический сбой или ошибку программирования.
Ожидаемую бизнес-ситуацию (отклонённая карта, отказ по заявке) выражайте результатом ветвящегося обработчика `BranchingStep`.

Исключение откатывает транзакцию обработчика: состояние, переменные и обрабатывавшееся событие остаются как были.
Затем движок вызывает `ProcessErrorHandler` процесса в отдельной транзакции, и его результат решает, что будет дальше.

## Политика ошибок

```java
@Override
public ProcessSpec define(ProcessSpecBuilder builder) {
    return builder
            .type(TYPE)
            // Повторяем упавший шаг 5 раз с экспоненциальной задержкой
            .onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(5)))
            // Если упавший шаг так и не был исполнен после 5 попыток, то переводим процесс в состояние CANCELLED
            .onGiveUp(CANCELLED)
            .initialState(CREATED)
            // ...
            .terminal(CANCELLED)
            .build();
}
```

Оба вызова необязательны: `onError` задаёт обработчик (по умолчанию `noRetry()`), `onGiveUp` — состояние, в которое
процесс переходит, когда обработчик сдаётся.

Обработчик должен имплементировать `ProcessErrorHandler`, его метод `handle` должен возвращать один из трёх результатов:

| Результат | Что происходит                                                                                                |
|---|---------------------------------------------------------------------------------------------------------------|
| `ErrorResult.retryAfter(delay)` | обработчик выполняется снова через `delay`                                                                    |
| `ErrorResult.giveUp()` | Процесс переходит в состояние `onGiveUp`. Если в процессе не указан `onGiveUp`, то останавливается в `FAILED` |
| `ErrorResult.fail()` | Процесс останавливается в статусе `FAILED` в текущем состоянии                   |

Встроенные обработчики:

| Обработчик | Поведение |
|---|---|
| `ProcessErrorHandler.noRetry()` | `giveUp()` при первой ошибке |
| `ProcessErrorHandler.fixed(maxRetries, delay)` | До `maxRetries` повторов с фиксированной задержкой, затем `giveUp()` |
| `ProcessErrorHandler.exponential(maxRetries, initialDelay, maxDelay)` | То же с удваивающейся задержкой |

Пример реализации своего обработчика ошибок:

```java
public class CustomProcessErrorHandler implements ProcessErrorHandler {
    public ErrorResult handle(ProcessContext context, Throwable cause) {
        if (cause instanceof PermanentlyUnavailableException) return ErrorResult.giveUp();
        if (cause instanceof DataCorruptionException) return ErrorResult.fail();
        if (context.attempt() < 3) return ErrorResult.retryAfter(Duration.ofSeconds(10));
        return ErrorResult.giveUp();
    }
}
```

## Состояние отказа

`giveUp()` — обычный переход в состояние объявленное в `onGiveUp`, например в шаг компенсации. Чтобы передать причину
ошибки - можно записать ее в переменную `ProcessContext`.
Ошибка в самом состоянии `onGiveUp` останавливает процесс в `FAILED`, чтобы падающая компенсация не зациклилась.

## Повторы в ожидании

Повтор приостанавливает всё ожидание (`await`): до повтора новые сообщения и наступившие таймеры не обрабатываются.
Что это значит для сроков и как с этим работать — в [awaits.md](awaits.md#ошибки-в-ожидании).

## Процессы в FAILED

Процесс в `FAILED` не выполняется: ни шаги, ни таймеры. Найдите такие процессы по статусу
([processes.md](processes.md#поиск-процессов)), устраните причину и продолжите через
`processEngine.resumeFailedProcess(id)`: упавший шаг или событие выполняется снова, а счёт попыток начинается
заново. Для процесса не в `FAILED` `resumeFailedProcess` бросает `ProcessStatusException`.

## Подробности

- Изменения `ProcessContext`, сделанные обработчиком ошибок, сохраняются атомарно с его результатом.
- Если обработчик ошибок сам бросил исключение или вернул `null`, упавшее событие повторяется через 60 секунд.
- Ветвящийся обработчик, вернувший `null`, падает с `IllegalStateException`, которое обрабатывает та же политика.
- Успешный обработчик сбрасывает `attempt` и `lastError`.
- `lastError` хранит `SimpleClassName: message`, обрезанный до 1024 символов. Stack trace не хранится: ищите его в
  логе по id процесса.
