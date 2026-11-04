package io.github.neherim.ruslo.core.api.exception;

import io.github.neherim.ruslo.core.api.definition.WorkerId;

/** No worker with the given id is registered in the engine. */
public class WorkerNotRegisteredException extends ProcessEngineException {

    public WorkerNotRegisteredException(WorkerId id) {
        super("Worker with id " + id + " is not registered");
    }
}
