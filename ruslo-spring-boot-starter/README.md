# Ruslo Spring Boot Starter

`ruslo-spring-boot-starter` wires a `ProcessEngine` into a Spring Boot application.

## Setup

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

The library is not yet published to a public Maven repository, so install the artifacts locally first:

```bash
./mvnw clean install
```

The auto-configuration is activated when the application has a `DataSource` and a `PlatformTransactionManager`.
Without any settings the starter runs one transactional worker `DEFAULT` (`max-concurrency: 10`), creates the
engine tables and starts the dispatcher. All `ruslo.*` properties are described in [docs/configuration.md](../docs/configuration.md).

## What the starter does

- creates a `ProcessEngine` bean on top of the Spring `DataSource` and `PlatformTransactionManager`; engine operations
  join the application's transaction;
- finds all `ProcessDefinition` beans and registers them in the engine;
- starts the engine after all beans are created but before the web server and message listeners, and stops it
  after them: HTTP requests and messages, including during graceful shutdown, find the engine running;
- wires Micrometer metrics if the context has a `MeterRegistry`.

## Definitions and dependency injection

A definition is a regular Spring bean; handlers are passed to it by constructor injection
(or through `bean(Type.class)` of the `SpringProcessDefinition` base class):

```java
@Component
@RequiredArgsConstructor
public class OrderProcessDefinition implements ProcessDefinition {
    private final CreateOrderStep createOrderStep;
    private final WaitingForPaymentHandlers waitingForPaymentHandlers;

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type("ORDER")
                .initialState(OrderState.CREATED)
                .step(OrderState.CREATED, createOrderStep, OrderState.WAITING_FOR_PAYMENT)
                .await(OrderState.WAITING_FOR_PAYMENT, await -> await
                        .message(PAYMENT_CONFIRMED, waitingForPaymentHandlers::onPaymentConfirmed,
                                OrderState.COMPLETED))
                .terminal(OrderState.COMPLETED)
                .build();
    }
}
```

The `ProcessEngine` bean is created without definitions, and definitions are registered later, so steps and the
services they use can inject `ProcessEngine` without a circular dependency.

`newProcess`, `sendMessage`, `sendMessageSync` and `resumeFailedProcess` are available only after the engine starts. A call from
`@PostConstruct` throws `IllegalStateException`; for startup actions use `ApplicationRunner`
or `ApplicationReadyEvent`.

## Customization

| What | How |
|---|---|
| Engine clock | a `Clock` bean named `processEngineClock` (default `Clock.systemDefaultZone()`). Other `Clock` beans of the application do not affect the engine, and the engine clock is not injected by type: declare and inject it with `@Bean(defaultCandidate = false)` / `@Qualifier("processEngineClock")` |
| A worker that YAML cannot describe (e.g. with its own executor) | a `WorkerSpec` bean; an entry with the same id in `ruslo.workers.*` takes precedence |
| A separate connection pool for the engine | your own `Database` bean: `new SpringJdbcDatabase(transactionManager, dataSource)` |
| Full control | your own `ProcessEngine` bean; the starter only starts and stops it, register definitions yourself |

Worker bean example:

```java
@Configuration
public class WorkerConfiguration {

    @Bean
    public WorkerSpec reportWorker() {
        return WorkerSpec.builder(new WorkerId("REPORT"))
                .nonTransactional()
                .maxConcurrency(20)
                .build();
    }
}
```
