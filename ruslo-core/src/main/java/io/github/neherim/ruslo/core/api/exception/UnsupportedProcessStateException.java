package io.github.neherim.ruslo.core.api.exception;

public class UnsupportedProcessStateException extends ProcessEngineException {

    public UnsupportedProcessStateException(String processType, String processState) {
        super("No handler found for state " + processState + " in process of type " + processType);
    }
}
