package io.github.neherim.ruslo.core.internal.instance;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.runtime.process.ProcessMessages;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ProcessMessagesTest {
    private static final MessageType<String> FIRST = MessageType.of("FIRST", String.class);
    private static final MessageType<String> SECOND = MessageType.of("SECOND", String.class);

    @Test
    void storesFifoAcrossTypesAndRoundTripsJson() {
        var messages = ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER);
        var received = Instant.parse("2026-09-24T10:15:00Z");
        messages.append(FIRST, "one", received);
        messages.append(SECOND, "two", received.plusSeconds(1));
        var json = messages.serializeToString();
        assertTrue(json.startsWith("["));
        var restored = ProcessMessages.fromString(JsonUtils.DEFAULT_OBJECT_MAPPER, json);
        var first = restored.takeFirst();
        assertEquals("one", restored.convert(first, FIRST).payload());
        assertEquals("two", restored.convert(restored.peekFirst().orElseThrow(), SECOND).payload());
        restored.clear();
        assertEquals("[]", restored.serializeToString());
    }

    @Test
    void acceptsNullAndDuplicateSignals() {
        var messages = ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER);
        var now = Instant.now();
        messages.append(FIRST, null, now);
        messages.append(FIRST, null, now);
        var first = messages.peekFirst().orElseThrow();
        assertNull(messages.convert(first, FIRST).payload());
        messages.takeFirst();
        assertFalse(messages.isEmpty());
    }

    @Test
    void equalReceptionTimesKeepAppendOrderAcrossReload() {
        var messages = ProcessMessages.empty(JsonUtils.DEFAULT_OBJECT_MAPPER);
        var received = Instant.parse("2026-09-24T10:15:00Z");
        messages.append(SECOND, "second", received);
        messages.append(FIRST, "first", received);
        var restored = ProcessMessages.fromString(JsonUtils.DEFAULT_OBJECT_MAPPER,
                messages.serializeToString());
        assertEquals("SECOND", restored.peekFirst().orElseThrow().type());
        restored.takeFirst();
        assertEquals("FIRST", restored.peekFirst().orElseThrow().type());
    }
}
