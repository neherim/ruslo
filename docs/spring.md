# Spring Boot

English | [Русский](ru/spring.md)

`ruslo-spring-boot-starter` builds the engine from the application's `DataSource` and transaction manager. It needs
no `ruslo.*` settings: on startup it creates the engine tables, a transactional `DEFAULT` worker and starts the
dispatcher. The settings for when the defaults do not fit are in [configuration.md](configuration.md).

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

The starter registers the `ProcessEngine` and `ProcessEngineAdmin` beans. Inject them where processes are started,
messages are sent or processes are managed manually.

## Definitions

Every `ProcessDefinition` bean is registered in the engine on startup. Extend `SpringProcessDefinition` to take
steps and handlers from the context with `bean(Type.class)`:

```java
@Component
public class OrderProcessDefinition extends SpringProcessDefinition {
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(TYPE)
            .initialState(CREATED)
            .step(CREATED, bean(CreateOrderStep.class), WAITING_FOR_PAYMENT)
            // ...
            .build();
    }
}
```

`define` is called after all singletons are created, so `bean(...)` causes no early initialization or circular
dependencies. Constructor injection into a plain `ProcessDefinition` bean works too.

## Properties

The properties are prefixed with `ruslo.*`; the full list is in [configuration.md](configuration.md#settings).
Unknown `ruslo.*` properties fail the startup, so a typo is not silently ignored.

Besides `ruslo.workers.*`, a worker can be declared as a `WorkerSpec` bean; a worker from the properties overrides a
bean with the same id.

## Application transactions

Engine operations join the active application transaction (`PROPAGATION_REQUIRED`). So `newProcess(...).start()` and
`sendMessage()` inside `@Transactional` are atomic with the application changes on the same `DataSource` and
transaction manager: on rollback neither is saved. Process steps run after commit, each in its own transaction.

An engine error (a database error, `ProcessNotFoundException`, an undeclared message type) is thrown inside the
transaction and, as usual in Spring, marks it rollback-only even if caught.

An expected rejection is thrown after the engine leaves its transaction: `MessageNotExpectedException` from
`sendMessage` and `sendMessageSync`, `ProcessStatusException` from `resumeFailedProcess`. A caught rejection does not
prevent the application transaction from committing; an uncaught one rolls it back like any exception.

## Tests

`ruslo-spring-boot-starter-test` adds `@ProcessEngineTest` and the emulator: [testing.md](testing.md).
