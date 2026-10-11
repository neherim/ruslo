# Ruslo Examples

`ruslo-examples` is the parent module for the examples. Each submodule is self-contained: it has its own `pom.xml` and tests.

## Modules

| Module | What it shows | Related documentation |
|---|---|---|
| [`quickstart`](quickstart) | Tutorial code: a minimal Spring Boot application with an order process | [`docs/quickstart.md`](../docs/quickstart.md), [`docs/concepts.md`](../docs/concepts.md) |
| [`cookbook`](cookbook) | Recipes for common tasks, one package per recipe. Runnable demo: Admin UI and Actuator metrics on the processes of all recipes | [`cookbook/README.md`](cookbook/README.md), [`docs/admin-ui.md`](../docs/admin-ui.md), [`docs/metrics.md`](../docs/metrics.md) |
| [`standalone-core`](standalone-core) | Use without Spring: `ruslo-core`, `PlainJdbcDatabase`, manual engine and worker configuration | [`docs/configuration.md`](../docs/configuration.md) |

## Commands

Test all examples:

```bash
./mvnw test -pl ruslo-examples/quickstart,ruslo-examples/cookbook,ruslo-examples/standalone-core -am
```

Test one example:

```bash
./mvnw test -pl ruslo-examples/quickstart -am
./mvnw test -pl ruslo-examples/cookbook -am
./mvnw test -pl ruslo-examples/standalone-core -am
```

Run the demo:

```bash
./mvnw spring-boot:run -pl ruslo-examples/cookbook -am
```

After startup:

- Admin UI: <http://localhost:8080/ruslo>
- Actuator metrics: <http://localhost:8080/actuator/metrics>

What to look at in Admin UI: [`cookbook/README.md`](cookbook/README.md#demo).
