package io.github.neherim.ruslo.core.spi;

import io.github.neherim.ruslo.core.api.exception.ProcessEngineException;

public class PlainJdbcDatabaseException extends ProcessEngineException {
    public PlainJdbcDatabaseException(String message) {
        super(message);
    }

    public PlainJdbcDatabaseException(String message, Throwable cause) {
        super(message, cause);
    }

    public PlainJdbcDatabaseException(Throwable cause) {
        super(cause);
    }
}
