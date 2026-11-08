package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectReader;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static io.github.neherim.ruslo.core.utils.JsonUtils.DEFAULT_OBJECT_MAPPER;

/** Message queue of the current await; cleared on every state entry. */
public final class ProcessMessages {
    private static final ObjectReader JSON_READER = DEFAULT_OBJECT_MAPPER.readerFor(
            new TypeReference<List<PendingMessage>>() {
            });

    private final List<PendingMessage> entries;

    private ProcessMessages(List<PendingMessage> entries) {
        this.entries = entries;
    }

    /** Creates an empty message queue. */
    public static ProcessMessages empty() {
        return new ProcessMessages(new ArrayList<>());
    }

    /** Reads queued messages, preserving decimal precision and scale in payloads. */
    public static ProcessMessages fromString(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Messages JSON cannot be blank");
        List<PendingMessage> messages = JSON_READER.readValue(json);
        if (messages.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null message entry");
        return new ProcessMessages(new ArrayList<>(messages));
    }

    public String serializeToString() {
        return DEFAULT_OBJECT_MAPPER.writeValueAsString(entries);
    }

    public void append(MessageType<?> type, Object payload, UUID id, Instant receivedAt) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
        Objects.requireNonNull(receivedAt);
        var tree = DEFAULT_OBJECT_MAPPER.valueToTree(payload);
        entries.add(new PendingMessage(id, type.name(), tree, receivedAt));
    }

    public Optional<PendingMessage> peekFirst() {
        // The row lock serializes appends; list order survives the JSON round trip even with equal receivedAt.
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
    }

    public void removeFirst() {
        entries.removeFirst();
    }

    public void clear() {
        entries.clear();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public Optional<Instant> firstReceivedAt() {
        return peekFirst().map(PendingMessage::receivedAt);
    }

    public <T> IncomingMessage<T> convert(PendingMessage entry, MessageType<T> type) {
        if (!entry.type().equals(type.name())) throw new IllegalArgumentException("Message type mismatch");
        T payload = entry.payload() == null || entry.payload().isNull()
                ? null
                : DEFAULT_OBJECT_MAPPER.treeToValue(entry.payload(), type.clazz());
        return new IncomingMessage<>(entry.id(), type, payload, entry.receivedAt());
    }

    public Object normalizePayload(Object payload, Class<?> payloadClass) {
        if (payload == null) return null;
        return DEFAULT_OBJECT_MAPPER.treeToValue(DEFAULT_OBJECT_MAPPER.valueToTree(payload), payloadClass);
    }

    /** A stored message not yet handled by the current await. */
    public record PendingMessage(UUID id, String type, JsonNode payload, Instant receivedAt) {
        public PendingMessage {
            Objects.requireNonNull(id);
            Objects.requireNonNull(type);
            Objects.requireNonNull(receivedAt);
        }
    }
}
