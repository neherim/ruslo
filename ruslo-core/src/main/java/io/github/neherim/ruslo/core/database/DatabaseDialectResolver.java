package io.github.neherim.ruslo.core.database;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Objects;

/** Resolves the SQL dialect from JDBC metadata. */
public final class DatabaseDialectResolver {
    private DatabaseDialectResolver() {
    }

    /**
     * @throws IllegalStateException if metadata is unavailable or the database is not supported
     */
    public static DatabaseDialect resolve(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "DataSource must not be null");
        try (var connection = dataSource.getConnection()) {
            var productName = connection.getMetaData().getDatabaseProductName();
            if (productName == null || productName.isBlank()) {
                throw new IllegalStateException("Unable to determine database dialect: "
                        + "JDBC database product name is missing");
            }
            var normalizedProductName = productName.toLowerCase(Locale.ROOT);
            if (normalizedProductName.contains("postgresql")) {
                return DatabaseDialect.POSTGRESQL;
            }
            if (normalizedProductName.contains("h2")) {
                return DatabaseDialect.H2;
            }
            throw new IllegalStateException("Unsupported database product '" + productName
                    + "'. Supported databases: PostgreSQL and H2");
        } catch (SQLException ex) {
            throw new IllegalStateException("Unable to determine database dialect from JDBC metadata", ex);
        }
    }
}
