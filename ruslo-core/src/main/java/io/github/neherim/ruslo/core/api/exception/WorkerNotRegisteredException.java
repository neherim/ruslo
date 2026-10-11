package io.github.neherim.ruslo.core.api.exception;

import io.github.neherim.ruslo.core.api.definition.WorkerId;

public class WorkerNotRegisteredException extends ProcessEngineException {

    public WorkerNotRegisteredException(WorkerId id) {
        super("Worker with id " + id + " is not registered");
    }
}
