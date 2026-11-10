package io.github.neherim.ruslo.core.api.test;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.TimerType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestProcessContextTest {
    private static final VariableType<Order> ORDER = VariableType.of("order", Order.class);
    private static final VariableType<Integer> AMOUNT = VariableType.of("amount", Integer.class);
    private static final VariableType<String> PAYMENT_ID = VariableType.of("paymentId", String.class);

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
    void timersAreTruncatedToMicrosecondsLikeInEngine() {
        var due = TimerType.of("due");
        var next = TimerType.of("next");
        var context = TestProcessContext.builder()
            .timer(due, Instant.parse("2026-10-07T10:00:00.123456789Z"))
            .build();

        assertEquals(Optional.of(Instant.parse("2026-10-07T10:00:00.123456Z")), context.findTimer(due));
        context.setTimer(next, Instant.parse("2026-10-08T10:00:00.000000999Z"));
        assertTrue(context.cancelTimer(due));
        assertFalse(context.cancelTimer(due));
        assertEquals(Map.of("next", Instant.parse("2026-10-08T10:00:00Z")), context.timers());
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
            .variable(AMOUNT, 100)
            .build();

        assertEquals(order, context.getVariable(ORDER));
        assertEquals(new BigDecimal("500.00"), context.getVariable(ORDER).amount());
        assertEquals(100, context.getVariable(AMOUNT));
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

        context.setVariable(PAYMENT_ID, "p-1");
        context.setVariable(ORDER, new Order("PS5", BigDecimal.TEN, List.of()));

        assertEquals("p-1", context.getVariable(PAYMENT_ID));
        assertTrue(context.removeVariable(ORDER));
        assertFalse(context.removeVariable(ORDER));
        assertEquals(Optional.empty(), context.findVariable(ORDER));
        assertEquals(Set.of("paymentId"), context.variableNames());
    }

    @Test
    void getVariableThrowsWithNameWhenVariableIsNotSet() {
        var context = TestProcessContext.create();

        var error = assertThrows(IllegalStateException.class, () -> context.getVariable(PAYMENT_ID));

        assertEquals("Variable 'paymentId' is not set", error.getMessage());
    }

    @Test
    void variableHoldingNullIsNotFound() {
        var context = TestProcessContext.builder().variable(PAYMENT_ID, null).build();

        assertEquals(Optional.empty(), context.findVariable(PAYMENT_ID));
        assertThrows(IllegalStateException.class, () -> context.getVariable(PAYMENT_ID));
    }
}
