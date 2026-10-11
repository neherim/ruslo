package io.github.neherim.ruslo.core.api.exception;

/** No definition is registered for the process type. */
public class UnsupportedProcessTypeException extends ProcessEngineException {

    public UnsupportedProcessTypeException(String processType) {
        super("No process definition registered for type " + processType);
    }
}
