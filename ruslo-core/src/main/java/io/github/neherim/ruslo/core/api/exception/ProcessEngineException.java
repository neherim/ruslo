package io.github.neherim.ruslo.core.api.exception;

/** Base class of engine exceptions. */
public class ProcessEngineException extends RuntimeException {
    public ProcessEngineException(String message) { super(message); }
    public ProcessEngineException(String message, Throwable cause) { super(message, cause); }
    public ProcessEngineException(Throwable cause) { super(cause); }
}
