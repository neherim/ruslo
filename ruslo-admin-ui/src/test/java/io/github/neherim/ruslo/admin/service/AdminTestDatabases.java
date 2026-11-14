package io.github.neherim.ruslo.admin.service;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

/** Isolates bulk integration tests on H2 or PostgreSQL selected by test.db. */
final class AdminTestDatabases {
    private AdminTestDatabases() {
    }

    static DataSource create() throws SQLException {
        if (!"postgresql".equals(System.getProperty("test.db", "h2"))) {
            var source = new JdbcDataSource();
            source.setURL("jdbc:h2:mem:bulk-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
            return pooled(source);
        }
        var source = new PGSimpleDataSource();
        source.setURL(Postgres.CONTAINER.getJdbcUrl());
        source.setUser(Postgres.CONTAINER.getUsername());
        source.setPassword(Postgres.CONTAINER.getPassword());
        var schema = "bulk_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        source.setCurrentSchema(schema);
        return pooled(source);
    }

    private static DataSource pooled(DataSource source) {
        var config = new HikariConfig();
        config.setDataSource(source);
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(1);
        return new HikariDataSource(config);
    }

    private static final class Postgres {
        private static final PostgreSQLContainer CONTAINER = start();

        private static PostgreSQLContainer start() {
            var container = new PostgreSQLContainer("postgres:18.4-alpine");
            container.start();
            return container;
        }
    }
}
