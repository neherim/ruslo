package io.github.neherim.ruslo.core.api.exception;

/** The process definition does not declare the requested state. */
public class UnsupportedProcessStateException extends ProcessEngineException {

    public UnsupportedProcessStateException(String processType, String processState) {
        super("No handler found for state " + processState + " in process of type " + processType);
    }
}
