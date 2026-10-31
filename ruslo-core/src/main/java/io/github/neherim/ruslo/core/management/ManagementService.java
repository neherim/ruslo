package io.github.neherim.ruslo.core.management;

import io.github.neherim.ruslo.core.database.DatabaseDialect;
import io.github.neherim.ruslo.core.spi.Database;

import java.util.Objects;

/** Engine management operations. */
public class ManagementService {
    private final Database database;
    private final DatabaseDialect databaseDialect;

    /** Creates the service with the given database dialect. */
    public ManagementService(Database database, DatabaseDialect databaseDialect) {
        this.database = Objects.requireNonNull(database, "Database must not be null");
        this.databaseDialect = Objects.requireNonNull(databaseDialect, "Database dialect must not be null");
    }

    /** Migrates the built-in database schema. */
    public void updateDatabaseSchema() {
        DatabaseSchemaMigrator.update(database.getDataSource(), databaseDialect);
    }
}
