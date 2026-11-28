# Spring Boot

`ruslo-spring-boot-starter` builds the engine from the application's `DataSource` and transaction manager. It needs
no `ruslo.*` settings: on startup it creates the engine tables, a transactional `DEFAULT` worker and starts the
dispatcher. The settings are for when the defaults do not fit.

```xml
<dependency>
    <groupId>io.github.neherim</groupId>
    <artifactId>ruslo-spring-boot-starter</artifactId>
    <version>0.1.0</version>
</dependency>
```

The starter registers `ProcessEngine` as `rusloProcessEngine` and `ProcessEngineAdmin` as `rusloProcessEngineAdmin`.
Inject them by type. Ruslo prefixes the bean names it assigns in its Spring modules with `ruslo`.

When upgrading from unprefixed bean names, update explicit references such as `@Qualifier` and `@Resource`.
If you replace the default worker bean by name, use `rusloDefaultWorker`.

To override system time for the engine, declare a `RusloTimeSource` bean with any name. The starter selects it by
type. If your application already supplies a `Clock`, you can use it as the source.

```java
@Bean
RusloTimeSource processTime(Clock applicationClock) {
    return applicationClock::instant;
}
```

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
dependencies. Constructor injection of handler beans into a `ProcessDefinition` works too.

## Properties

The properties are prefixed with `ruslo.*`; the full list is in [configuration.md](configuration.md#settings).
Unknown `ruslo.*` properties fail the startup, so a typo is not silently ignored.
Workers are declared with the `ruslo.workers.*` properties or as `WorkerSpec` beans: [workers.md](workers.md#registration).

## Application transactions

Engine operations join the active application transaction (`PROPAGATION_REQUIRED`). So `newProcess(...).start()` and
`sendMessage()` inside `@Transactional` are atomic with the application changes on the same `DataSource` and
transaction manager: on rollback neither is saved. Process steps run after commit, each in its own transaction.

An engine error (a database error, `ProcessNotFoundException`, an undeclared message type) is thrown inside the
transaction and, as usual in Spring, marks it rollback-only even if caught.

An expected rejection is returned, not thrown: `MessageDelivery` from `sendMessage` and `sendMessageSync`,
`ResumeResult` from `resumeFailedProcess`. A returned rejection does not mark the transaction rollback-only: the
caller can act on it and still commit. `MessageDelivery.orThrow()` and `ResumeResult.orThrow()` turn a rejection
into an exception; thrown from application code, it follows the application's own exception rules, not the
engine's.

## Tests

`ruslo-spring-boot-starter-test` adds `@ProcessEngineTest` and the emulator: [testing.md](testing.md).
