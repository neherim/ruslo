package io.github.neherim.ruslo.core.internal.instance;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonCreator;
import io.github.neherim.ruslo.core.runtime.process.ProcessVariables;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessVariablesTest {
    private static final LocalDateTime DATE_TIME = LocalDateTime.parse("2026-05-10T10:15:30");

    private final ObjectMapper om = JsonUtils.DEFAULT_OBJECT_MAPPER;

    @Test
    void stringVariable() {
        var first = ProcessVariables.empty(om)
            .setVariable("varName", "Hello");

        var second = ProcessVariables.fromString(om, first.serializeToString());

        assertEquals("Hello", first.getVariable("varName", String.class));
        assertEquals("Hello", second.getVariable("varName", String.class));
    }

    @Test
    void booleanVariable() {
        var first = ProcessVariables.empty(om)
            .setVariable("varName", true);

        var second = ProcessVariables.fromString(om, first.serializeToString());

        assertEquals(true, first.getVariable("varName", Boolean.class));
        assertEquals(true, second.getVariable("varName", Boolean.class));
    }

    @Test
    void localDateTimeVariable() {
        var first = ProcessVariables.empty(om)
            .setVariable("varName", DATE_TIME);

        var second = ProcessVariables.fromString(om, first.serializeToString());

        assertEquals(DATE_TIME, first.getVariable("varName", LocalDateTime.class));
        assertEquals(DATE_TIME, second.getVariable("varName", LocalDateTime.class));
    }

    @Test
    void recordVariable() {
        record Rec(String a, Integer b) {
        }

        var recordVariable = new Rec("Hello!", 42);

        var first = ProcessVariables.empty(om)
            .setVariable("varName", recordVariable);

        var second = ProcessVariables.fromString(om, first.serializeToString());

        assertEquals(recordVariable, first.getVariable("varName", Rec.class));
        assertEquals(recordVariable, second.getVariable("varName", Rec.class));
    }

    @Test
    void recordVariableWithOptional() {
        record Rec(Optional<String> a, Optional<Integer> b) {
        }

        var recordVariable = new Rec(Optional.empty(), Optional.of(42));

        var first = ProcessVariables.empty(om)
            .setVariable("varName", recordVariable);

        var second = ProcessVariables.fromString(om, first.serializeToString());

        assertEquals(recordVariable, first.getVariable("varName", Rec.class));
        assertEquals(recordVariable, second.getVariable("varName", Rec.class));
    }

    @Test
    void listVariable() {
        var list = List.of("Hello", "World!");

        var first = ProcessVariables.empty(om)
            .setVariable("varName", list);

        var second = ProcessVariables.fromString(om, first.serializeToString());

        assertEquals(list, first.getVariable("varName", List.class));
        assertEquals(list, second.getVariable("varName", List.class));
    }

    @Test
    void bigDecimalVariable() {
        var num = new BigDecimal("9.99999999999999999999999");

        var first = ProcessVariables.empty(om)
            .setVariable("varName", num);

        var second = ProcessVariables.fromString(om, first.serializeToString());

        assertEquals(num, first.getVariable("varName", BigDecimal.class));
        assertEquals(num, second.getVariable("varName", BigDecimal.class));
    }

    @Test
    void bigDecimalVariableKeepsScale() {
        var num = new BigDecimal("1.10");

        var second = ProcessVariables.fromString(om, ProcessVariables.empty(om)
            .setVariable("varName", num)
            .serializeToString());

        assertEquals(num, second.getVariable("varName", BigDecimal.class));
    }

    @Test
    void variablesAreSerializedAsSingleJsonObject() {
        var json = ProcessVariables.empty(om)
            .setVariable("orderId", "A-1")
            .setVariable("customer", Map.of("name", "Bob"))
            .serializeToString();

        assertEquals(om.readTree("{\"orderId\":\"A-1\",\"customer\":{\"name\":\"Bob\"}}"), om.readTree(json));
    }

    @Test
    void rewriteVariableValue() {
        var variables = ProcessVariables.empty(om)
            .setVariable("var1", "Hello")
            .setVariable("var1", 42);

        assertEquals(42, variables.getVariable("var1", Integer.class));
    }

    @Test
    void classWithJsonCreatorConstructorWithoutJsonPropertyAnnotations() {
        var context = new Context("string", 10, 6.3);
        var first = ProcessVariables.empty(om)
            .setVariable("varName", context);

        var firstContext = first.getVariable("varName", Context.class);
        assertNotNull(firstContext);
        assertEquals("string", firstContext.stringValue);
        assertEquals(10, firstContext.intValue);
        assertEquals(6.3, firstContext.doubleValue);
    }

    @Test
    void asMapShouldReturnRawJsonAndBeImmutable() {
        var variables = ProcessVariables.empty(om)
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
