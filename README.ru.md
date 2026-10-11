# Ruslo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.neherim/ruslo-core)](https://central.sonatype.com/namespace/io.github.neherim)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)

[English](README.md) | Русский

Встраиваемый движок долгоживущих бизнес-процессов для Java и Spring Boot. Вы описываете процесс как конечный
автомат на Java DSL, а движок выполняет его прямо внутри вашего приложения и хранит состояние в той базе данных,
которая у вас уже есть. Без отдельного кластера, брокера и BPMN.

![Карточка процесса в Admin UI](docs/images/admin-ui-process.png)

## Возможности

- **Одна БД транзакция с вашими данными.** Шаг меняет данные приложения атомарно с переходом процесса.
- **Без инфраструктуры.** Движок — это библиотека, которая работает с БД вашего приложения: PostgreSQL в production,
  H2 для тестов и локального запуска. Не нужно разворачивать сервер, брокер или кластер.
- **Типизированный DSL.** Типы сообщений и формы обработчиков проверяет компилятор: обработчик, не подходящий к
  объявлению, не скомпилируется. Остальную структуру графа `build()` проверяет при старте.
- **Надёжность.** Каждый переход фиксируется в БД, поэтому процесс переживает рестарт и падение узла.
- **Ожидание без потоков.** Процесс ждёт сообщение или таймер (минуты, дни, недели) и не занимает поток.
- **Горизонтальное масштабирование.** Несколько экземпляров приложения разбирают процессы через общую БД.
- **Весь процесс в одном месте.** Граф переходов описан на Java DSL, Admin UI рисует по нему схему.
- **Детерминированные тесты.** Эмулятор выполняет шаги и сдвигает время без `sleep`.

## Пример

Заказ создаётся, затем ждёт оплату. Если оплата не пришла за 2 часа, заказ отменяется.

```java
public enum OrderState implements ProcessState {
    CREATED, WAITING_FOR_PAYMENT, PAID, CANCELLED
}

@Component
public class CreateOrderStep implements ProcessStep {
    @Override
    public void execute(ProcessContext context) {
        // бизнес-логика; изменения в БД коммитятся вместе с переходом
    }
}

@Component
public class OrderProcess extends SpringProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER");
    public static final MessageType<PaymentConfirmed> PAYMENT_CONFIRMED =
            MessageType.of("paymentConfirmed", PaymentConfirmed.class);

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
                .initialState(CREATED)
                .step(CREATED, bean(CreateOrderStep.class), WAITING_FOR_PAYMENT)
                .await(WAITING_FOR_PAYMENT, await -> await
                        .message(PAYMENT_CONFIRMED, bean(PaymentHandler.class), PAID)
                        .timeout(Duration.ofHours(2), CANCELLED))
                .terminal(PAID)
                .terminal(CANCELLED)
                .build();
    }
}
```

`PaymentHandler` реализует `ProcessMessageHandler<PaymentConfirmed>`. Обработчики только выполняют действие,
а следующее состояние задаёт definition, поэтому весь граф переходов виден в одном месте.

Процесс запускается и получает сообщения через `ProcessEngine`:

```java
UUID orderId = processEngine.newProcess(OrderProcess.TYPE).start();

// позже, например в обработчике callback от платёжной системы
processEngine.sendMessage(orderId, OrderProcess.PAYMENT_CONFIRMED, new PaymentConfirmed(amount));
```

`sendMessage` сохраняет сообщение в транзакции вызывающего кода, обработчик выполняется после коммита.

Полный пример с тестом — в [быстром старте](docs/ru/quickstart.md).

## Подключение

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Требования: Java 21, Spring Boot 4, PostgreSQL (H2 подходит для тестов и локального запуска). При старте
starter сам создаёт таблицы движка.

Дополнительные модули:

| Артефакт | Зачем |
|---|---|
| `ruslo-core` | Движок без Spring: [конфигурация без Spring](docs/ru/configuration.md#конфигурация-без-spring) |
| `ruslo-admin-ui` | Web-интерфейс: поиск процессов, схема, ручное вмешательство|
| `ruslo-spring-boot-starter-test` | `@ProcessEngineTest` и эмулятор для тестов (scope `test`) |

## Когда не подходит

- Нужна очередь задач без состояний. Хватит Kafka, RabbitMQ или db-scheduler.
- Нужны параллельные ветки внутри одного процесса.
- Нужно версионирование процессов: уже запущенные процессы используют текущую definition, её версия не сохраняется.
- Нужен исполняемый BPMN или визуальный редактор процессов. Подойдут Camunda или Flowable.
- Нужна оркестрация между сервисами на разных языках с отдельным кластером. Подойдёт Temporal.

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
обработка ошибок, тестирование и типичные ошибки на одной странице. В Claude Code положите его в проект:

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
