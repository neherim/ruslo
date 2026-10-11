package io.github.neherim.ruslo.core.api.definition;

/**
 * Message handler; on success the process moves to the state declared in the definition.
 * To choose the target state, use {@link BranchingMessageHandler}.
 *
 * @param <T> payload type
 */
@FunctionalInterface
public interface ProcessMessageHandler<T> {
    /** Handles the message. */
    void handle(ProcessContext context, IncomingMessage<T> message);
}
