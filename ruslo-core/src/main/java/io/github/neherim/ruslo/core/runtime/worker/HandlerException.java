package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.exception.ProcessEngineException;

/** Wraps a failure of a user step or event handler to tell it apart from an engine system error. */
public class HandlerException extends ProcessEngineException {
    HandlerException(RuntimeException cause) {
        super(cause);
    }

    @Override
    public synchronized RuntimeException getCause() {
        return (RuntimeException) super.getCause();
    }
}
