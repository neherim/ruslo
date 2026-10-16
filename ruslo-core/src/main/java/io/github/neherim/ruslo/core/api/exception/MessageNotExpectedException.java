package io.github.neherim.ruslo.core.api.exception;

import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.util.Set;
import java.util.UUID;

/**
 * The process does not accept the message now: the current state does not await this message, the process is
 * completed, or, for a synchronous send, the process is {@code FAILED}. {@link #getStatus()} tells these cases
 * apart.
 */
public class MessageNotExpectedException extends ProcessEngineException {
    private final UUID processId;
    private final String messageName;
    private final ProcessStatus status;
    private final ProcessStateId currentState;
    private final Set<String> expectedMessageNames;

    /**
     * @param expectedMessageNames messages the current state awaits
     */
    public MessageNotExpectedException(
            UUID processId,
            String messageName,
            ProcessStatus status,
            ProcessStateId currentState,
            Set<String> expectedMessageNames
    ) {
        super("Message " + messageName + " is not expected for " + status + " process " + processId
                + " in state " + currentState.name() + "; awaited: " + expectedMessageNames);
        this.processId = processId;
        this.messageName = messageName;
        this.status = status;
        this.currentState = currentState;
        this.expectedMessageNames = Set.copyOf(expectedMessageNames);
    }

    public UUID getProcessId() {
        return processId;
    }

    public String getMessageName() {
        return messageName;
    }

    public ProcessStatus getStatus() {
        return status;
    }

    public ProcessStateId getCurrentState() {
        return currentState;
    }

    /** Returns the messages the current state awaits; a {@code FAILED} process does not handle them synchronously. */
    public Set<String> getExpectedMessageNames() {
        return expectedMessageNames;
    }
}
