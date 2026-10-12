package io.github.neherim.ruslo.perf.config;

import org.testcontainers.postgresql.PostgreSQLContainer;

/** Creates an isolated PostgreSQL container for a benchmark class. */
public final class BenchmarkPostgres {
    public static final String IMAGE = "postgres:18.4-alpine";

    private BenchmarkPostgres() {
    }

    /**
     * Creates a container with fixed test database credentials.
     *
     * @return a new container whose lifecycle is managed by JUnit
     */
    public static PostgreSQLContainer container() {
        return new PostgreSQLContainer(IMAGE)
            .withDatabaseName("ruslo")
            .withUsername("ruslo")
            .withPassword("ruslo_test")
            .withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on",
                    "-c", "full_page_writes=on");
    }
}
