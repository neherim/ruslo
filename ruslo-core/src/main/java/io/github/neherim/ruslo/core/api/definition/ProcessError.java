package io.github.neherim.ruslo.core.api.definition;

import java.util.Objects;

/**
 * Step or event handler failure passed to {@link ProcessErrorHandler}. Process id, type and state are available
 * from {@link ProcessContext}.
 *
 * @param cause   exception thrown by the handler
 * @param attempt number of previous failed attempts in the current state
 */
public record ProcessError(Throwable cause, int attempt) {
    public ProcessError {
        Objects.requireNonNull(cause, "Cause cannot be null");
    }
}
