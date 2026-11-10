package io.github.neherim.ruslo.core.api.definition;

import java.time.Instant;
import java.util.Objects;

/**
 * Message delivered to an await handler.
 */
public record IncomingMessage<T>(MessageType<T> type, T payload, Instant receivedAt) {
    public IncomingMessage {
        Objects.requireNonNull(type, "Message type cannot be null");
        Objects.requireNonNull(receivedAt, "Receive time cannot be null");
    }
}
