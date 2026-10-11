package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Message queue of the current await; cleared on every state entry. */
public final class ProcessMessages {
    private static final TypeReference<List<PendingMessage>> MESSAGE_LIST = new TypeReference<>() {};

    private final ObjectMapper mapper;
    private final List<PendingMessage> entries;

    private ProcessMessages(ObjectMapper mapper, List<PendingMessage> entries) {
        this.mapper = Objects.requireNonNull(mapper);
        this.entries = entries;
    }

    public static ProcessMessages empty(ObjectMapper mapper) {
        return new ProcessMessages(mapper, new ArrayList<>());
    }

    public static ProcessMessages fromString(ObjectMapper mapper, String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Messages JSON cannot be blank");
        var messages = new ArrayList<>(mapper.readValue(json, MESSAGE_LIST));
        if (messages.stream().anyMatch(Objects::isNull)) throw new IllegalArgumentException("Null message entry");
        return new ProcessMessages(mapper, messages);
    }

    public String serializeToString() {
        return mapper.writeValueAsString(entries);
    }

    public void append(MessageType<?> type, Object payload, UUID id, Instant receivedAt) {
        Objects.requireNonNull(type);
        Objects.requireNonNull(id);
        Objects.requireNonNull(receivedAt);
        var tree = mapper.valueToTree(payload);
        entries.add(new PendingMessage(id, type.name(), tree, receivedAt));
    }

    public Optional<PendingMessage> peekFirst() {
        // The row lock serializes appends; list order survives the JSON round trip even with equal receivedAt.
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.getFirst());
    }

    public void remove(UUID id) {
        if (!entries.removeIf(entry -> entry.id().equals(id))) {
            throw new IllegalStateException("Selected message is no longer pending: " + id);
        }
    }

    public void clear() { entries.clear(); }
    public boolean isEmpty() { return entries.isEmpty(); }
    public Optional<Instant> firstReceivedAt() { return peekFirst().map(PendingMessage::receivedAt); }

    public <T> IncomingMessage<T> convert(PendingMessage entry, MessageType<T> type) {
        if (!entry.type().equals(type.name())) throw new IllegalArgumentException("Message type mismatch");
        T payload = entry.payload() == null || entry.payload().isNull()
            ? null : mapper.treeToValue(entry.payload(), type.clazz());
        return new IncomingMessage<>(entry.id(), type, payload, entry.receivedAt());
    }

    public Object normalizePayload(Object payload, Class<?> payloadClass) {
        if (payload == null) return null;
        return mapper.treeToValue(mapper.valueToTree(payload), payloadClass);
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
