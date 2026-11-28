package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.util.Set;
import java.util.UUID;

/**
 * Result of delivering a message to a process: {@link ProcessEngine#sendMessage} queues the message, and
 * {@link ProcessEngine#sendMessageSync} handles it in the calling thread.
 * <p>
 * A rejection depends on the current process state, so it is returned instead of thrown: an exception thrown
 * inside the engine transaction would mark it rollback-only even if caught. Use {@link #orThrow()} to turn a
 * rejection into a {@link MessageNotExpectedException} where the caller needs one.
 */
public sealed interface MessageDelivery {

    /**
     * The message is stored for the current await: queued by {@link ProcessEngine#sendMessage}, handled in the
     * calling thread by {@link ProcessEngine#sendMessageSync}. For a queued message this means stored, not that
     * it will be handled.
     */
    record Delivered() implements MessageDelivery {}

    /**
     * The process does not accept the message now: the current state does not await this message, the process is
     * completed, or, for a synchronous send, the process is {@code FAILED}. Nothing is stored.
     *
     * @param expectedMessageNames messages the current state awaits; for a {@code FAILED} process rejected by a
     *        synchronous send the set may contain the rejected message: it is awaited, but cannot be handled
     *        synchronously
     * @param reason why the message is rejected
     */
    record NotExpected(
            UUID processId,
            String messageName,
            ProcessStatus status,
            ProcessState currentState,
            Set<String> expectedMessageNames,
            String reason
    ) implements MessageDelivery {
        public Set<String> expectedMessageNames() {
            return Set.copyOf(expectedMessageNames);
        }
    }

    /**
     * Returns {@code this} if the message is delivered, throws a {@link MessageNotExpectedException} for a
     * rejection.
     */
    default Delivered orThrow() {
        return switch (this) {
            case Delivered d -> d;
            case NotExpected n -> throw new MessageNotExpectedException(n);
        };
    }
}
