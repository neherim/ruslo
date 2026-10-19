# Ruslo

[![CI](https://github.com/neherim/ruslo/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/neherim/ruslo/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.neherim/ruslo-core)](https://central.sonatype.com/namespace/io.github.neherim)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)

[English](README.md) | Русский

Встраиваемый движок долгоживущих бизнес-процессов для Java и Spring Boot. Вы описываете процесс как конечный
автомат на Java DSL, а движок выполняет его прямо внутри вашего приложения и хранит состояние в той базе данных,
которая у вас уже есть. Без отдельного кластера, брокера и BPMN.

Типичные задачи:

- заказ ждёт оплату, потом доставку и отменяется, если срок истёк;
- онбординг клиента днями ждёт документы и отправляет напоминания;
- вызов внешнего API повторяется с задержками, а если API продолжает падать, процесс уходит в компенсирующее
  состояние.

![Карточка процесса в Admin UI](docs/images/admin-ui-process.png)

## Возможности

- **Одна БД транзакция с вашими данными.** Шаг меняет данные приложения атомарно с переходом процесса.
- **Без инфраструктуры.** Движок — это библиотека, которая работает с БД вашего приложения: PostgreSQL в production,
  H2 для тестов и локального запуска. Не нужно разворачивать сервер, брокер или кластер.
- **Типизированный DSL.** Типы сообщений и формы обработчиков проверяет компилятор.
- **Детерминированные тесты.** Тестовый эмулятор движка выполняет шаги и сдвигает время без `sleep`.

Как и другие персистентные движки процессов, Ruslo фиксирует каждый переход, поэтому процесс переживает рестарт и
падение узла; ждёт сообщение или таймер (минуты, дни, недели), не занимая поток; распределяет процессы между
несколькими экземплярами приложения через общую БД.

## Пример

Заказ ждёт оплату. Если оплата не пришла за 2 часа, заказ отменяется.

```java
// Объявляем список состояний процесса
public enum OrderState implements ProcessState {
    CREATED, WAITING_FOR_PAYMENT, PAID, CANCELLED
}

// Описываем обработчик шага процесса
@Component
@RequiredArgsConstructor
public class ReserveStockStep implements ProcessStep {
    private final OrderRepository orders;

    @Override
    public void execute(ProcessContext context) {
        long orderId = context.getVariable(OrderProcess.ORDER_ID);
        // Весь метод execute находится в той же БД транзакции, в которой будет записан переход процесса в следующее состояние
        orders.reserveStock(orderId);
    }
}

// Описываем саму схему процесса и переходов между состояниями
@Component
public class OrderProcess extends SpringProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER");
    public static final VariableType<Long> ORDER_ID = VariableType.of("orderId");
    public static final MessageType<PaymentConfirmed> PAYMENT_CONFIRMED =
            MessageType.of("paymentConfirmed", PaymentConfirmed.class);

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
                .initialState(CREATED)
                // bean(...) берёт обработчик из Spring-контекста
                .step(CREATED, bean(ReserveStockStep.class), WAITING_FOR_PAYMENT)
                .await(WAITING_FOR_PAYMENT, await -> await
                        .message(PAYMENT_CONFIRMED, bean(PaymentHandler.class), PAID)
                        .timeout(Duration.ofHours(2), CANCELLED))
                .terminal(PAID)
                .terminal(CANCELLED)
                .build();
    }
}
```

Процесс запускается и получает сообщения через `ProcessEngine`:

```java
// запуск процесса
UUID processId = processEngine.newProcess(OrderProcess.TYPE)
        .businessKey(order.number())
        .variable(OrderProcess.ORDER_ID, order.id())
        .start();

// позже, например в обработчике callback от платёжной системы
processEngine.sendMessage(processId, OrderProcess.PAYMENT_CONFIRMED, new PaymentConfirmed(amount));
```

`start` и `sendMessage` присоединяются к транзакции вызывающего кода, шаг или обработчик выполняется после коммита.
По business key процесс можно найти по номеру заказа в поиске и в Admin UI.

Полный пример с тестом — в [быстром старте](docs/ru/quickstart.md).

## Подключение

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Требования: Java 21, Spring Boot 4, PostgreSQL (H2 подходит для тестов и локального запуска). 
При запуске движок сам создает таблицы в БД.

Дополнительные модули:

| Артефакт | Зачем |
|---|---|
| `ruslo-core` | Движок без Spring: [конфигурация без Spring](docs/ru/configuration.md#конфигурация-без-spring) |
| `ruslo-admin-ui` | Web-интерфейс: поиск процессов, схема, ручное вмешательство|
| `ruslo-spring-boot-starter-test` | `@ProcessEngineTest` и эмулятор для тестов (scope `test`) |

## Производительность

С пустыми обработчиками на одном PostgreSQL один движок выполняет около 4 800 шагов/с
с транзакционным worker. Каждый переход — это коммит в БД, поэтому пропускную способность ограничивает база:
два или четыре экземпляра на одной БД добавляют 14–28%. Условия и сырые данные:
[отчёт о производительности](docs/performance/2026-10-11-1-d5c2eb6/performance-report.md) (на английском).

## Когда не подходит

- Production-БД — не PostgreSQL: в данный момент движок поддерживает только PostgreSQL и H2.
- Приложение работает на Spring Boot 3: starter требует Spring Boot 4.
- Нужен стабильный API уже сейчас: в 0.x несовместимые изменения возможны в минорных версиях.
- Нужна очередь задач без состояний. Лучше подойдут Kafka, RabbitMQ или db-scheduler.
- Нужно версионирование процессов: уже запущенные процессы используют текущую definition, её версия не сохраняется.
- Нужен исполняемый BPMN или визуальный редактор процессов. Лучше подойдут Camunda или Flowable.
- Нужна оркестрация между сервисами на разных языках с отдельным кластером. Лучше подойдёт Temporal.

## Документация

| Раздел | О чём |
|---|---|
| [Быстрый старт](docs/ru/quickstart.md) | Рабочий процесс заказа от зависимостей до теста |
| [Модель процесса](docs/ru/concepts.md) | Шаги, ожидания, ветвления, таймеры, переменные, эволюция definition |
| [Гарантии](docs/ru/guarantees.md) | Что переживает сбой, доставка сообщений, точность таймеров |
| [Обработка ошибок](docs/ru/error-handling.md) | Ретраи с задержкой, запасное состояние |
| [Workers](docs/ru/workers.md) | Отдельные пулы для шагов с внешним I/O |
| [Исполнение](docs/ru/execution.md) | Точные правила выбора событий и границ транзакций |
| [Конфигурация](docs/ru/configuration.md) | Все свойства `ruslo.*`; использование без Spring |
| [Тестирование](docs/ru/testing.md) | `@ProcessEngineTest`, эмулятор, таймеры, юнит-тесты шагов |
| [Эксплуатация](docs/ru/operations.md) | Production-чеклист, поиск процессов, очистка завершённых |
| [Admin UI](docs/ru/admin-ui.md) | Просмотр и управление процессами |
| [Метрики](docs/ru/metrics.md) | Micrometer, список метрик |

Готовые примеры: [`ruslo-examples`](ruslo-examples/README.md).

## AI-агенты

[`skills/ruslo/SKILL.md`](skills/ruslo/SKILL.md) — skill для AI-агентов: модель процесса, типы обработчиков,
обработка ошибок, тестирование, настройки, типовые паттерны и ошибки на одной странице. Доступ в сеть ему не нужен;
он описывает последнюю версию библиотеки. В Claude Code положите его в проект:

```bash
mkdir -p .claude/skills/ruslo
curl -o .claude/skills/ruslo/SKILL.md https://raw.githubusercontent.com/neherim/ruslo/main/skills/ruslo/SKILL.md
```

Для других агентов сошлитесь на файл из `AGENTS.md` или файла правил вашего агента.

## Статус

Версия 0.x: несовместимые изменения публичного API (`io.github.neherim.ruslo.core.api.*` и `core.spi.*`)
возможны в минорных версиях. Остальные пакеты внутренние.

## Лицензия

[Apache License 2.0](LICENSE). Как собрать проект и запустить тесты, описано в [CONTRIBUTING.md](CONTRIBUTING.md).
