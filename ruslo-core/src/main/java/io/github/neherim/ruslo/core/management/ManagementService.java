package io.github.neherim.ruslo.core.management;

import io.github.neherim.ruslo.core.api.DatabaseDialect;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.DatabaseDialectResolver;

import java.util.Objects;

/** Engine management operations. */
public class ManagementService {
    private final Database database;
    private final DatabaseDialect databaseDialect;

    /** Creates the service with the database dialect detected from the database. */
    public ManagementService(Database database) {
        this(database, resolveDialect(database));
    }

    /** Creates the service with the given database dialect. */
    public ManagementService(Database database, DatabaseDialect databaseDialect) {
        this.database = Objects.requireNonNull(database, "Database must not be null");
        this.databaseDialect = Objects.requireNonNull(databaseDialect, "Database dialect must not be null");
    }

    /** Migrates the built-in database schema. */
    public void updateDatabaseSchema() {
        DatabaseSchemaMigrator.update(database.getDataSource(), databaseDialect);
    }

    private static DatabaseDialect resolveDialect(Database database) {
        Objects.requireNonNull(database, "Database must not be null");
        return DatabaseDialectResolver.resolve(database.getDataSource());
    }
}
