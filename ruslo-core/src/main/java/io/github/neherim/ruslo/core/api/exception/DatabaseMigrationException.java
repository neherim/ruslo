package io.github.neherim.ruslo.core.api.exception;

/** Built-in schema migration failed. */
public class DatabaseMigrationException extends ProcessEngineException {
    public DatabaseMigrationException(String message) {
        super(message);
    }

    public DatabaseMigrationException(String message, Throwable cause) {
        super(message, cause);
    }
}
