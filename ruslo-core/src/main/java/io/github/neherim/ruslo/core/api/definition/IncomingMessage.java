package io.github.neherim.ruslo.core.api.definition;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Message delivered to an await handler. */
public record IncomingMessage<T>(UUID id, MessageType<T> type, T payload, Instant receivedAt) {
    public IncomingMessage {
        Objects.requireNonNull(id, "Message id cannot be null");
        Objects.requireNonNull(type, "Message type cannot be null");
        Objects.requireNonNull(receivedAt, "Receive time cannot be null");
    }
}
