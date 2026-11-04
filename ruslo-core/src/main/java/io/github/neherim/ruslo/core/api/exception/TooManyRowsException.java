package io.github.neherim.ruslo.core.api.exception;

/** A query expected to return at most one result returned more. */
public class TooManyRowsException extends ProcessEngineException {
    public TooManyRowsException(String message) {
        super(message);
    }
}
