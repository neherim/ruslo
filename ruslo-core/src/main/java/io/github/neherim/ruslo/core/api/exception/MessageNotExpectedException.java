package io.github.neherim.ruslo.core.api.exception;

import io.github.neherim.ruslo.core.api.MessageDelivery;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.util.Set;
import java.util.UUID;

/**
 * The process does not accept the message now: the current state does not await this message, the process is
 * completed, or, for a synchronous send, the process is {@code FAILED}. {@link #getStatus()} tells these cases
 * apart; the exception message states the reason.
 * <p>
 * A diagnostic exception created from {@link MessageDelivery.NotExpected}. Thrown by the operator APIs
 * {@link io.github.neherim.ruslo.core.api.ProcessEngineAdmin#sendMessage(UUID, String, Object)} and
 * {@link io.github.neherim.ruslo.core.api.ProcessEngineEmulator#sendMessageAndRunUntilStop}, and by
 * {@link MessageDelivery#orThrow()}.
 */
public class MessageNotExpectedException extends ProcessEngineException {
    private final UUID processId;
    private final String messageName;
    private final ProcessStatus status;
    private final ProcessState currentState;
    private final Set<String> expectedMessageNames;

    /**
     * Creates the exception from the rejection returned by a message delivery operation.
     */
    public MessageNotExpectedException(MessageDelivery.NotExpected rejection) {
        super("Message " + rejection.messageName() + " is not expected for " + rejection.status()
                + " process " + rejection.processId() + " in state " + rejection.currentState().name()
                + ": " + rejection.reason());
        this.processId = rejection.processId();
        this.messageName = rejection.messageName();
        this.status = rejection.status();
        this.currentState = rejection.currentState();
        this.expectedMessageNames = rejection.expectedMessageNames();
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

    public ProcessState getCurrentState() {
        return currentState;
    }

    /**
     * Returns the messages the current state awaits. For a {@code FAILED} process rejected by a synchronous send,
     * the list may contain the rejected message: it is awaited, but cannot be handled synchronously.
     */
    public Set<String> getExpectedMessageNames() {
        return expectedMessageNames;
    }
}
