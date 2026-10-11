# Ruslo

[![Maven Central](https://img.shields.io/maven-central/v/io.github.neherim/ruslo-core)](https://central.sonatype.com/namespace/io.github.neherim)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue)](LICENSE)
![Java 21](https://img.shields.io/badge/Java-21-orange)

English | [Русский](README.ru.md)

An embedded engine for long-running business processes in Java and Spring Boot. You describe a process as a state
machine in a Java DSL, and the engine runs it right inside your application, keeping its state in the database you
already have. No separate cluster, broker or BPMN.

![Process page in the Admin UI](docs/images/admin-ui-process.png)

## Features

- **One database transaction with your data.** A step changes application data atomically with the process transition.
  Temporal and message brokers cannot do this: their state lives outside your database.
- **No infrastructure.** The engine is a library that uses your application's database: PostgreSQL in production,
  H2 for tests and local runs. There is no server, broker or cluster to deploy.
- **Typed DSL.** Message payloads and handler forms are checked by the compiler: a handler that does not match its
  declaration does not compile. `build()` checks the rest of the graph at startup.
- **Durability.** Every transition is committed to the database, so a process survives restarts and node failures.
- **Waiting without threads.** A process waits for a message or a timer (minutes, days, weeks) without holding a thread.
- **Horizontal scaling.** Several application instances share processes through the common database.
- **The whole process in one place.** The transition graph is defined in a Java DSL; the Admin UI draws a diagram from it.
- **Deterministic tests.** The emulator runs steps and moves time forward without `sleep`.

## Example

An order is created, then waits for payment. If the payment does not arrive within 2 hours, the order is cancelled.

```java
public enum OrderState implements ProcessState {
    CREATED, WAITING_FOR_PAYMENT, PAID, CANCELLED
}

@Component
public class CreateOrderStep implements ProcessStep {
    @Override
    public void execute(ProcessContext context) {
        // business logic; database changes are committed together with the transition
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

`PaymentHandler` implements `ProcessMessageHandler<PaymentConfirmed>`. Handlers only perform an action and the
definition declares the next state, so the whole transition graph is visible in one place.

A process is started and receives messages through `ProcessEngine`:

```java
UUID orderId = processEngine.newProcess(OrderProcess.TYPE).start();

// later, e.g. in the payment callback
processEngine.sendMessage(orderId, OrderProcess.PAYMENT_CONFIRMED, new PaymentConfirmed(amount));
```

`sendMessage` stores the message in the caller's transaction; the handler runs after commit.

The full example with a test is in the [quickstart](docs/quickstart.md).

## Installation

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requirements: Java 21, Spring Boot 4, PostgreSQL (H2 works for tests and local runs). The starter creates the
engine tables on startup.

Additional modules:

| Artifact | Purpose |
|---|---|
| `ruslo-core` | Engine without Spring: [configuration without Spring](docs/configuration.md#configuration-without-spring) |
| `ruslo-admin-ui` | Web UI: process search, diagram, manual intervention |
| `ruslo-spring-boot-starter-test` | `@ProcessEngineTest` and the emulator for tests (scope `test`) |

## When not to use it

- You need a stateless task queue. Kafka, RabbitMQ or db-scheduler is enough.
- You need parallel branches within one process.
- You need process versioning: running processes use the current definition; its version is not stored.
- You need executable BPMN or a visual process editor. Use Camunda or Flowable.
- You need orchestration across services in different languages with a dedicated cluster. Use Temporal.

## Documentation

| Section | Contents |
|---|---|
| [Quickstart](docs/quickstart.md) | A working order process, from dependencies to a test |
| [Process model](docs/concepts.md) | Steps, awaits, branching, timers, variables, definition evolution |
| [Guarantees](docs/guarantees.md) | What survives a failure, message delivery, timer accuracy |
| [Error handling](docs/error-handling.md) | Delayed retries, fallback state |
| [Workers](docs/workers.md) | Separate pools for steps with external I/O |
| [Execution](docs/execution.md) | Exact rules of event selection and transaction boundaries |
| [Configuration](docs/configuration.md) | All `ruslo.*` properties; usage without Spring |
| [Testing](docs/testing.md) | `@ProcessEngineTest`, emulator, timers, step unit tests |
| [Operations](docs/operations.md) | Production checklist, process search, cleanup of completed processes |
| [Admin UI](docs/admin-ui.md) | Viewing and managing processes |
| [Metrics](docs/metrics.md) | Micrometer, list of metrics |

Ready-to-run examples: [`ruslo-examples`](ruslo-examples/README.md).

## AI coding agents

[`skills/ruslo/SKILL.md`](skills/ruslo/SKILL.md) is a skill for coding agents: the process model, handler types,
error handling, testing and common mistakes on one page. In Claude Code, put it in your project:

```bash
mkdir -p .claude/skills/ruslo
curl -o .claude/skills/ruslo/SKILL.md https://raw.githubusercontent.com/neherim/ruslo/main/skills/ruslo/SKILL.md
```

For other agents, reference the file from `AGENTS.md` or your agent's rules file.

## Status

Version 0.x: incompatible changes to the public API (`io.github.neherim.ruslo.core.api.*` and `core.spi.*`)
are possible in minor versions. Other packages are internal.

## License

[Apache License 2.0](LICENSE). How to build the project and run the tests is described in [CONTRIBUTING.md](CONTRIBUTING.md).
