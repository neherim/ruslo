# Developing Ruslo

Requires Java 21, and Docker for the PostgreSQL tests only.

```bash
./mvnw clean compile                                          # compile
./mvnw test                                                   # all tests (H2)
./mvnw test -pl ruslo-core -Ppostgresql-tests                 # core tests on PostgreSQL (needs Docker)
./mvnw test -Pperf-tests                                      # performance tests, see ruslo-performance-test
./mvnw spring-boot:run -pl ruslo-examples/cookbook -am        # demo: http://localhost:8080, Admin UI at /ruslo
```

Every pull request runs [CI](.github/workflows/ci.yml): `./mvnw verify` (tests on H2 and the Spotless check) on
Java 21 and 25, and the core tests on PostgreSQL. Run `./mvnw verify` before opening a pull request.

Code is formatted by the Eclipse formatter (`eclipse-formatter.xml`) through Spotless, and imports are grouped the
IntelliJ IDEA way (other, `javax`/`java`, static). Run `./mvnw spotless:apply` before committing. The formatter keeps
line breaks you make and only wraps lines longer than 120 characters.

Modules, architecture and code rules are described in [AGENTS.md](AGENTS.md).
