package io.github.neherim.ruslo.core.api.definition;

/**
 * Message handler that chooses the next state: it returns an outcome, and the definition maps each outcome to a
 * state with {@link ProcessSpecBuilder.Routes#on}.
 *
 * @param <T> payload type
 * @param <O> outcome enum
 */
@FunctionalInterface
public interface BranchingMessageHandler<T, O extends Enum<O>> {
    /**
     * Handles the message and returns a non-null outcome.
     */
    O handle(ProcessContext context, IncomingMessage<T> message);
}
