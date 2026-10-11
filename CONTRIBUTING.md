# Developing Ruslo

Requires Java 21, and Docker for the PostgreSQL tests only.

```bash
./mvnw clean compile                                          # compile
./mvnw test                                                   # all tests (H2)
./mvnw test -pl ruslo-core -Ppostgresql-tests                 # core tests on PostgreSQL (needs Docker)
./mvnw test -Pperf-tests                                      # performance tests, see ruslo-performance-test
./mvnw spring-boot:run -pl ruslo-examples/cookbook -am        # demo: http://localhost:8080, Admin UI at /ruslo
```

Modules, architecture and code rules are described in [AGENTS.md](AGENTS.md).
