package io.github.neherim.ruslo.core.api.definition;

/**
 * Message handler. On success, the process moves to the target state declared in the definition, which can be the
 * current state.
 * To select a target by outcome, use {@link BranchingMessageHandler}.
 *
 * @param <T> payload type
 */
@FunctionalInterface
public interface LinearMessageHandler<T> {
    /**
     * Handles the message.
     */
    void handle(ProcessContext ctx, IncomingMessage<T> message);
}
