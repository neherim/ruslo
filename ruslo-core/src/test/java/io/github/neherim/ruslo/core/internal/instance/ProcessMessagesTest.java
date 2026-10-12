package io.github.neherim.ruslo.core.internal.instance;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.runtime.process.ProcessMessages;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessMessagesTest {
    private static final MessageType<String> FIRST = MessageType.of("FIRST", String.class);
    private static final MessageType<String> SECOND = MessageType.of("SECOND", String.class);

    @Test
    void storesFifoAcrossTypesAndRoundTripsJson() {
        var messages = ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER);
        var firstId = UUID.randomUUID();
        var secondId = UUID.randomUUID();
        var received = Instant.parse("2026-09-24T10:15:00Z");
        messages.append(FIRST, "one", firstId, received);
        messages.append(SECOND, "two", secondId, received.plusSeconds(1));
        var json = messages.serializeToString();
        assertTrue(json.startsWith("["));
        var restored = ProcessMessages.fromString(JsonUtils.DEFAULT_OBJECT_MAPPER, json);
        assertEquals(firstId, restored.peekFirst().orElseThrow().id());
        assertEquals("one", restored.convert(restored.peekFirst().orElseThrow(), FIRST).payload());
        restored.remove(firstId);
        assertEquals(secondId, restored.peekFirst().orElseThrow().id());
        restored.clear();
        assertEquals("[]", restored.serializeToString());
    }

    @Test
    void acceptsNullAndDuplicateSignals() {
        var messages = ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER);
        var now = Instant.now();
        messages.append(FIRST, null, UUID.randomUUID(), now);
        messages.append(FIRST, null, UUID.randomUUID(), now);
        var first = messages.peekFirst().orElseThrow();
        assertNull(messages.convert(first, FIRST).payload());
        messages.remove(first.id());
        assertFalse(messages.isEmpty());
    }

    @Test
    void equalReceptionTimesKeepAppendOrderAcrossReload() {
        var messages = ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER);
        var received = Instant.parse("2026-09-24T10:15:00Z");
        var largerId = new UUID(0, 2);
        var smallerId = new UUID(0, 1);
        messages.append(FIRST, "first", largerId, received);
        messages.append(SECOND, "second", smallerId, received);
        var restored = ProcessMessages.fromString(JsonUtils.DEFAULT_OBJECT_MAPPER,
                messages.serializeToString());
        assertEquals(largerId, restored.peekFirst().orElseThrow().id());
        restored.remove(largerId);
        assertEquals(smallerId, restored.peekFirst().orElseThrow().id());
    }

    @Test
    void rejectsRemovingAbsentSignal() {
        var messages = ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER);
        assertThrows(IllegalStateException.class, () -> messages.remove(UUID.randomUUID()));
    }
}
