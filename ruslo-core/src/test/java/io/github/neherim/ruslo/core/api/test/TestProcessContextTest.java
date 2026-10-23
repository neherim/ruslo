package io.github.neherim.ruslo.core.api.test;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestProcessContextTest {
    private static final VariableType<Order> ORDER = VariableType.of("order", Order.class);

    record Order(String product, BigDecimal amount, List<String> tags) {
    }

    @Test
    void nowIsFixedByBuilderAndSystemTimeByDefault() {
        var fixed = Instant.parse("2026-10-07T10:00:00Z");
        assertEquals(fixed, TestProcessContext.builder().now(fixed).build().now());

        var before = Instant.now();
        var now = TestProcessContext.create().now();
        assertFalse(now.isBefore(before));
    }

    @Test
    void createUsesDefaults() {
        var context = TestProcessContext.create();

        assertNotNull(context.processId());
        assertNotNull(context.correlationId());
        assertEquals(ProcessType.of("test"), context.processType());
        assertEquals(ProcessState.of("test"), context.state());
        assertEquals(0, context.attempt());
        assertEquals(Optional.empty(), context.businessKey());
        assertTrue(context.variableNames().isEmpty());
    }

    @Test
    void builderSetsMetadata() {
        var processId = UUID.randomUUID();
        var context = TestProcessContext.builder()
            .processId(processId)
            .correlationId("corr-1")
            .processType(ProcessType.of("order"))
            .state(ProcessState.of("PAYMENT"))
            .attempt(2)
            .businessKey("order-42")
            .build();

        assertEquals(processId, context.processId());
        assertEquals("corr-1", context.correlationId());
        assertEquals(ProcessType.of("order"), context.processType());
        assertEquals(ProcessState.of("PAYMENT"), context.state());
        assertEquals(2, context.attempt());
        assertEquals(Optional.of("order-42"), context.businessKey());
    }

    @Test
    void variablesRoundTripThroughJson() {
        var order = new Order("PS5", new BigDecimal("500.00"), List.of("gift"));
        var context = TestProcessContext.builder()
            .variable(ORDER, order)
            .variable("amount", 100)
            .build();

        assertEquals(order, context.getVariable(ORDER));
        assertEquals(new BigDecimal("500.00"), context.getVariable(ORDER).amount());
        assertEquals(100, context.getVariable("amount", Integer.class));
        assertEquals(Set.of("order", "amount"), context.variableNames());
    }

    @Test
    void changingReadValueWithoutSetVariableIsNotStored() {
        var context = TestProcessContext.builder()
            .variable(ORDER, new Order("PS5", BigDecimal.ONE, new ArrayList<>()))
            .build();

        context.getVariable(ORDER).tags().add("lost");

        assertTrue(context.getVariable(ORDER).tags().isEmpty());
    }

    @Test
    void setAndRemoveVariables() {
        var context = TestProcessContext.create();

        context.setVariable("paymentId", "p-1");
        context.setVariable(ORDER, new Order("PS5", BigDecimal.TEN, List.of()));

        assertEquals("p-1", context.getVariable("paymentId", String.class));
        assertTrue(context.removeVariable(ORDER));
        assertFalse(context.removeVariable(ORDER));
        assertNull(context.getVariable(ORDER));
        assertEquals(Set.of("paymentId"), context.variableNames());
    }

    @Test
    void usesCustomJsonMapper() {
        var mapper = JsonMapper.builder().build();
        var context = TestProcessContext.builder().jsonMapper(mapper).variable("n", 1).build();

        assertEquals(1, context.getVariable("n", Integer.class));
    }
}
