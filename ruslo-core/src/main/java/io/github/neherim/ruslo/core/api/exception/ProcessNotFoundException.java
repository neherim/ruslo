package io.github.neherim.ruslo.core.api.exception;

import java.util.UUID;

/** No process with the given id exists. */
public class ProcessNotFoundException extends ProcessEngineException {

    public ProcessNotFoundException(UUID processId) {
        super("Process " + processId + " not found");
    }
}
