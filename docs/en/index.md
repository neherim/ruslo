# Ruslo

An embedded engine for long-running business processes in Java and Spring Boot. You describe a process as a state
machine in a Java DSL, and the engine runs it right inside your application, keeping its state in the database you
already have. No separate cluster, broker or BPMN.

Typical uses:

- an order waits for payment, then for delivery, and is cancelled if a deadline passes;
- customer onboarding waits days for documents and sends reminders;
- a call to an external API is retried with delays, and the process falls back to a compensating state if the API
  keeps failing.

![Process page in the Admin UI](../images/admin-ui-process.png)

## Installation

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

Requirements: Java 21, Spring Boot 4, PostgreSQL (H2 works for tests and local runs).

## Where to start

1. [Quickstart](quickstart.md): a working order process, from dependencies to a test.
2. [Process model](concepts.md): steps, awaits, branching, timers, variables, definition evolution.

The reference sections are in the navigation. Features, limitations and performance figures are in the
[README](https://github.com/neherim/ruslo#readme); ready-to-run examples are in
[`ruslo-examples`](https://github.com/neherim/ruslo/tree/main/ruslo-examples).
