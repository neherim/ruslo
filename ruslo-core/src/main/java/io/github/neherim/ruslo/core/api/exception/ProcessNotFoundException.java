package io.github.neherim.ruslo.core.api.exception;

import io.github.neherim.ruslo.core.api.definition.ProcessType;

import java.util.UUID;

/** No process matches the given id or the given type and business key. */
public class ProcessNotFoundException extends ProcessEngineException {

    public ProcessNotFoundException(UUID processId) {
        super("Process " + processId + " not found");
    }

    public ProcessNotFoundException(ProcessType processType, String businessKey) {
        super("Process of type " + processType.name() + " with business key '" + businessKey + "' not found");
    }
}
