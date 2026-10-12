package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.DatabaseDialect;
import org.h2.jdbcx.JdbcDataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Source of empty isolated databases for tests.
 * <p>
 * By default every call creates a new in-memory H2 database. With {@code -Dtest.db=postgresql}
 * all tests share one PostgreSQL container per JVM and every call gets its own schema.
 */
public final class TestDatabases {
    private static final DatabaseDialect DIALECT = switch (System.getProperty("test.db", "h2")) {
        case "h2" -> DatabaseDialect.H2;
        case "postgresql" -> DatabaseDialect.POSTGRESQL;
        default -> throw new IllegalStateException("Unsupported test.db: " + System.getProperty("test.db"));
    };

    private TestDatabases() {
    }

    public static DatabaseDialect dialect() {
        return DIALECT;
    }

    /**
     * Creates a new empty database of the selected dialect without the ruslo schema.
     */
    public static DataSource newDataSource() {
        return switch (DIALECT) {
            case H2 -> newH2DataSource();
            case POSTGRESQL -> newPostgreSqlDataSource();
        };
    }

    /**
     * Creates a new empty in-memory H2 database regardless of the selected dialect.
     */
    public static JdbcDataSource newH2DataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static DataSource newPostgreSqlDataSource() {
        var container = PostgreSql.CONTAINER;
        var schema = "t_" + UUID.randomUUID().toString().replace("-", "");
        var dataSource = new PGSimpleDataSource();
        dataSource.setURL(container.getJdbcUrl());
        dataSource.setUser(container.getUsername());
        dataSource.setPassword(container.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create test schema " + schema, e);
        }
        dataSource.setCurrentSchema(schema);
        return dataSource;
    }

    private static final class PostgreSql {
        private static final PostgreSQLContainer CONTAINER = start();

        private static PostgreSQLContainer start() {
            var container = new PostgreSQLContainer("postgres:18.4-alpine")
                .withCommand("postgres", "-c", "max_connections=300", "-c", "fsync=off");
            container.start();
            return container;
        }
    }
}
