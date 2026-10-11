package io.github.neherim.ruslo.core.api.exception;

import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.util.UUID;

/** The operation does not apply to the process in its current status. */
public class ProcessStatusException extends ProcessEngineException {
    private final UUID processId;
    private final ProcessStatus status;

    /**
     * @param reason why the operation does not apply in this status
     */
    public ProcessStatusException(UUID processId, ProcessStatus status, String reason) {
        super("Process " + processId + " is " + status + ": " + reason);
        this.processId = processId;
        this.status = status;
    }

    public UUID getProcessId() { return processId; }
    public ProcessStatus getStatus() { return status; }
}
