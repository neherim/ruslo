package io.github.neherim.ruslo.core.api.exception;

import java.util.Set;
import java.util.UUID;

/** The current state does not await this message, e.g. because the process is completed. */
public class MessageNotExpectedException extends ProcessEngineException {
    private final UUID processId;
    private final String messageName;
    private final String currentState;
    private final Set<String> expectedMessageNames;

    /**
     * @param expectedMessageNames messages the current state accepts
     */
    public MessageNotExpectedException(UUID processId, String messageName, String currentState,
                                       Set<String> expectedMessageNames) {
        super("Message " + messageName + " is not expected for process " + processId
            + " in state " + currentState + "; expected: " + expectedMessageNames);
        this.processId = processId;
        this.messageName = messageName;
        this.currentState = currentState;
        this.expectedMessageNames = Set.copyOf(expectedMessageNames);
    }

    public UUID getProcessId() { return processId; }
    public String getMessageName() { return messageName; }
    public String getCurrentState() { return currentState; }
    public Set<String> getExpectedMessageNames() { return expectedMessageNames; }
}
