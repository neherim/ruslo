# Обработка ошибок

[English](../error-handling.md) | Русский

Исключение шага, обработчика сообщения или таймера означает технический сбой или ошибку программиста,
а не штатный бизнес-результат. Ожидаемую бизнес-ситуацию выражайте outcome ветвящегося обработчика.

Исключение откатывает транзакцию обработчика: состояние, переменные и упавшее событие (сообщение или наступивший
таймер) остаются прежними. Затем движок в отдельной транзакции вызывает `ProcessErrorHandler`, который решает,
что делать дальше.

## ProcessErrorHandler

```java
ErrorResult handle(ProcessContext context, ProcessError error);
```

`ProcessError` содержит:

- `cause` — исключение обработчика;
- `attempt` — количество предыдущих неудачных попыток в текущем состоянии (`0` для первой ошибки). В ожидании это
  ошибки ожидания подряд, какие бы события ни упали.

Остальное (id, тип, состояние процесса, переменные) читайте из `ProcessContext`.

Handler возвращает один из трёх результатов:

- `ErrorResult.retryAfter(delay)` — повторить через `delay`. Шаг выполняется снова; ожидание выполняется снова и
  выбирает самое раннее наступившее событие, обычно упавшее. Новые сообщения во время ожидания повтора принимаются,
  но ни они, ни наступившие таймеры не обрабатываются раньше него (см.
  [execution.md](execution.md#выбор-события-ожидания)).
- `ErrorResult.fail()` — остановить процесс в текущем состоянии со статусом `FAILED`. Продолжить можно через
  `resumeFailedProcess(id)`, который повторит упавшее событие.
- `ErrorResult.fallback()` — уйти в запасное состояние процесса (см. ниже), а если его нет — в `FAILED`.

## Запасное состояние

Обработка ошибок объявляется на уровне процесса:

```java
.onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(1), Duration.ofMinutes(5)),
         CANCELLING)   // fallbackState: куда уйти, когда повторы исчерпаны
.initialState(CREATED)
// ...
.step(CANCELLING, cancelOrderStep, CANCELLED)
.terminal(CANCELLED)
```

Формы: `onError(handler)` — без запасного состояния, и `onError(handler, fallbackState)`.

`fallback()` выполняет обычный переход в `fallbackState` (при выходе из ожидания очередь сообщений очищается).
Процесс остаётся в `FAILED`, если `fallbackState` не объявлен или ошибка произошла в самом `fallbackState` —
иначе падающая компенсация зациклилась бы. **Косвенные циклы** (`CANCELLING → X`, ошибка в `X` → `CANCELLING`)
движок не обнаруживает — следите за топологией definition сами.

Причину перехода можно передать через `ProcessContext`: handler записывает переменную, запасное состояние её читает.

## Стандартные handlers

| Handler | Поведение |
|---|---|
| `ProcessErrorHandler.noRetry()` | Сразу возвращает `fallback()` |
| `ProcessErrorHandler.fixed(maxRetries, delay)` | До `maxRetries` повторов с фиксированной задержкой, затем `fallback()` |
| `ProcessErrorHandler.exponential(maxRetries, initialDelay, maxDelay)` | То же с удваивающейся задержкой |

Политика по умолчанию — `noRetry()`: без `fallbackState` процесс сразу уходит в `FAILED`.
**С объявленным `fallbackState` `noRetry()` переводит процесс в запасное состояние сразу после первой ошибки.**

Пример собственного handler:

```java
(context, error) -> {
    if (error.cause() instanceof PermanentlyUnavailableException) return ErrorResult.fallback();
    if (error.cause() instanceof DataCorruptionException) return ErrorResult.fail();
    if (error.attempt() < 3) return ErrorResult.retryAfter(Duration.ofSeconds(10));
    return ErrorResult.fallback();
}
```

## Гарантии

- Транзакция упавшего обработчика откатывается до вызова error handler; handler видит последнее сохранённое
  состояние процесса.
- Изменения `ProcessContext`, сделанные error handler, сохраняются атомарно с его результатом.
- При `retryAfter` и `fail` упавшее событие не считается обработанным.
- Если error handler сам бросает исключение или возвращает `null`, упавшее событие повторяется через 60 секунд;
  в `lastError` сохраняется исходная ошибка.
- Ветвящийся обработчик, вернувший `null`, считается упавшим с `IllegalStateException` и проходит через ту же политику.
- Успешный обработчик сбрасывает `attempt` и `lastError`. Крайний случай: если во время паузы ретрая таймер
  `at` перенесли на момент раньше упавшего события, после паузы вместо повтора срабатывает `at`; если его цель — само
  ожидание, его успех сбрасывает `attempt`.
- `lastError` хранит текст `ПростоеИмяКласса: сообщение` (`UnsupportedCountryException: Unsupported country: XX`),
  без сообщения — только имя класса. Текст обрезается до 1024 символов. Стек и причины (`getCause()`) не сохраняются:
  их ищите в логе по id процесса.
- `resumeFailedProcess` применим только к `FAILED`. Для активного или завершённого процесса он бросает
  `ProcessStatusException` и ничего не меняет; транзакцию вызывающего кода исключение само не помечает для отката.
- В `FAILED` процесс не исполняется: диспетчер его не выбирает, таймеры не срабатывают, `sendMessageSync`
  бросает `MessageNotExpectedException` с `getStatus() == FAILED`, а шаги эмулятора — `ProcessStatusException`. `sendMessage` по-прежнему
  принимает сообщения ожидания: они остаются в очереди и обрабатываются после `resumeFailedProcess`.
- `resumeFailedProcess` сбрасывает `attempt`: политика повторов после возобновления начинает отсчёт заново.
