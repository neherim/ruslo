package io.github.neherim.ruslo.core.internal.instance;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.neherim.ruslo.core.runtime.process.ProcessVariables;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static io.github.neherim.ruslo.core.utils.JsonUtils.DEFAULT_OBJECT_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessVariablesTest {
    private static final LocalDateTime DATE_TIME = LocalDateTime.parse("2026-05-10T10:15:30");

    @Test
    void stringVariable() {
        var first = ProcessVariables.empty()
            .setVariable("varName", "Hello");

        var second = ProcessVariables.fromString(first.serializeToString());

        assertEquals("Hello", first.findVariable("varName", String.class).orElseThrow());
        assertEquals("Hello", second.findVariable("varName", String.class).orElseThrow());
    }

    @Test
    void booleanVariable() {
        var first = ProcessVariables.empty()
            .setVariable("varName", true);

        var second = ProcessVariables.fromString(first.serializeToString());

        assertEquals(true, first.findVariable("varName", Boolean.class).orElseThrow());
        assertEquals(true, second.findVariable("varName", Boolean.class).orElseThrow());
    }

    @Test
    void localDateTimeVariable() {
        var first = ProcessVariables.empty()
            .setVariable("varName", DATE_TIME);

        var second = ProcessVariables.fromString(first.serializeToString());

        assertEquals(DATE_TIME, first.findVariable("varName", LocalDateTime.class).orElseThrow());
        assertEquals(DATE_TIME, second.findVariable("varName", LocalDateTime.class).orElseThrow());
    }

    @Test
    void recordVariable() {
        record Rec(String a, Integer b) {
        }

        var recordVariable = new Rec("Hello!", 42);

        var first = ProcessVariables.empty()
            .setVariable("varName", recordVariable);

        var second = ProcessVariables.fromString(first.serializeToString());

        assertEquals(recordVariable, first.findVariable("varName", Rec.class).orElseThrow());
        assertEquals(recordVariable, second.findVariable("varName", Rec.class).orElseThrow());
    }

    @Test
    void recordVariableWithOptional() {
        record Rec(Optional<String> a, Optional<Integer> b) {
        }

        var recordVariable = new Rec(Optional.empty(), Optional.of(42));

        var first = ProcessVariables.empty()
            .setVariable("varName", recordVariable);

        var second = ProcessVariables.fromString(first.serializeToString());

        assertEquals(recordVariable, first.findVariable("varName", Rec.class).orElseThrow());
        assertEquals(recordVariable, second.findVariable("varName", Rec.class).orElseThrow());
    }

    @Test
    void listVariable() {
        var list = List.of("Hello", "World!");

        var first = ProcessVariables.empty()
            .setVariable("varName", list);

        var second = ProcessVariables.fromString(first.serializeToString());

        assertEquals(list, first.findVariable("varName", List.class).orElseThrow());
        assertEquals(list, second.findVariable("varName", List.class).orElseThrow());
    }

    @Test
    void bigDecimalVariable() {
        var num = new BigDecimal("9.99999999999999999999999");

        var first = ProcessVariables.empty()
            .setVariable("varName", num);

        var second = ProcessVariables.fromString(first.serializeToString());

        assertEquals(num, first.findVariable("varName", BigDecimal.class).orElseThrow());
        assertEquals(num, second.findVariable("varName", BigDecimal.class).orElseThrow());
    }

    @Test
    void bigDecimalVariableKeepsScale() {
        var num = new BigDecimal("1.10");

        var second = ProcessVariables.fromString(ProcessVariables.empty()
            .setVariable("varName", num)
            .serializeToString());

        assertEquals(num, second.findVariable("varName", BigDecimal.class).orElseThrow());
    }

    @Test
    void variablesAreSerializedAsSingleJsonObject() {
        var json = ProcessVariables.empty()
            .setVariable("orderId", "A-1")
            .setVariable("customer", Map.of("name", "Bob"))
            .serializeToString();

        assertEquals(DEFAULT_OBJECT_MAPPER.readTree("{\"orderId\":\"A-1\",\"customer\":{\"name\":\"Bob\"}}"),
                DEFAULT_OBJECT_MAPPER.readTree(json));
    }

    @Test
    void rewriteVariableValue() {
        var variables = ProcessVariables.empty()
            .setVariable("var1", "Hello")
            .setVariable("var1", 42);

        assertEquals(42, variables.findVariable("var1", Integer.class).orElseThrow());
    }

    @Test
    void classWithJsonCreatorConstructorWithoutJsonPropertyAnnotations() {
        var context = new Context("string", 10, 6.3);
        var first = ProcessVariables.empty()
            .setVariable("varName", context);

        var firstContext = first.findVariable("varName", Context.class).orElseThrow();
        assertNotNull(firstContext);
        assertEquals("string", firstContext.stringValue);
        assertEquals(10, firstContext.intValue);
        assertEquals(6.3, firstContext.doubleValue);
    }

    @Test
    void asMapShouldReturnRawJsonAndBeImmutable() {
        var variables = ProcessVariables.empty()
            .setVariable("var1", "Hello")
            .setVariable("var2", 42);

        var raw = variables.asMap();
        assertEquals(Map.of("var1", "\"Hello\"", "var2", "42"), raw);
        assertThrows(UnsupportedOperationException.class, () -> raw.put("var3", "1"));
    }

    @JsonAutoDetect(fieldVisibility = ANY)
    public static class Context {

        private final String stringValue;
        private final int intValue;
        private final double doubleValue;

        @JsonCreator
        // parameters need no @JsonProperty annotations
        public Context(String stringValue, int intValue, double doubleValue) {
            this.stringValue = stringValue;
            this.intValue = intValue;
            this.doubleValue = doubleValue;
        }
    }
}
