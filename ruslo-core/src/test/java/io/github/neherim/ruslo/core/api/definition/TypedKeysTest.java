package io.github.neherim.ruslo.core.api.definition;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TypedKeysTest {
    private record Items(List<String> values) {
    }

    @Test
    void concreteRecordsSupportCollectionsAndBothFactories() {
        MessageType<Items> inferredMessage = MessageType.of("items");
        VariableType<Items> inferredVariable = VariableType.of("items");
        assertEquals(MessageType.of("items", Items.class), inferredMessage);
        assertEquals(VariableType.of("items", Items.class), inferredVariable);
    }

    @Test
    void genericClassesFailFastWithRecordHint() {
        var messageFailure = assertThrows(IllegalArgumentException.class, () -> MessageType.of("items", List.class));
        assertTrue(messageFailure.getMessage().contains("record"));
        assertThrows(IllegalArgumentException.class, () -> VariableType.of("items", Map.class));
        assertThrows(IllegalArgumentException.class, () -> {
            MessageType<List<String>> inferred = MessageType.of("items");
        });
        assertThrows(IllegalArgumentException.class, () -> {
            VariableType<Map<String, String>> inferred = VariableType.of("items");
        });
        assertThrows(IllegalArgumentException.class, () -> new MessageType<>("items", List.class));
        assertThrows(IllegalArgumentException.class, () -> new VariableType<>("items", Map.class));
    }

    @Test
    void namesAndClassesAreValidatedByConstructorsAndFactories() {
        for (String name : new String[]{null, "", " "}) {
            assertThrows(IllegalArgumentException.class, () -> MessageType.of(name, String.class));
            assertThrows(IllegalArgumentException.class, () -> VariableType.of(name, String.class));
            assertThrows(IllegalArgumentException.class, () -> new VariableType<>(name, String.class));
        }
        assertThrows(NullPointerException.class, () -> MessageType.of("key", (Class<String>) null));
        assertThrows(NullPointerException.class, () -> VariableType.of("key", (Class<String>) null));
    }
}
