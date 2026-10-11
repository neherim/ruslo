package io.github.neherim.ruslo.core.api.definition;

/**
 * Message handler whose returned outcome selects the target state from the definition's routes.
 *
 * @param <T> payload type
 * @param <O> outcome enum
 */
@FunctionalInterface
public interface BranchingMessageHandler<T, O extends Enum<O>> {
    /** Handles the message and returns a non-null outcome. */
    O handle(ProcessContext context, IncomingMessage<T> message);
}
