package io.github.neherim.ruslo.examples.cookbook.saga;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class SagaTest {
    private static final BigDecimal AMOUNT = new BigDecimal("99.00");
    private static final BigDecimal TOO_MUCH = new BigDecimal("5000.00");

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @MockitoSpyBean
    private Warehouse warehouse;
    @MockitoSpyBean
    private Payments payments;
    @MockitoSpyBean
    private Delivery delivery;

    @Test
    void fulfillsOrderWithoutCompensation() {
        var orderId = newOrderId();

        var completed = emulator.runUntilStop(startOrder(orderId, AMOUNT));

        assertTrue(completed.state().is(OrderFulfillmentDefinition.State.COMPLETED));
        verify(delivery).schedule(orderId, "Berlin");
        verify(payments, never()).refund(any());
        verify(warehouse, never()).release(any());
    }

    @Test
    void declinedPaymentCancelsReservation() {
        var orderId = newOrderId();

        var cancelled = emulator.runUntilStop(startOrder(orderId, TOO_MUCH));

        assertTrue(cancelled.state().is(OrderFulfillmentDefinition.State.CANCELLED));
        verify(delivery, never()).schedule(any(), any());
        verifyCompensated(orderId);
    }

    @Test
    void deliveryFailureCompensatesAfterRetries() {
        var orderId = newOrderId();
        doThrow(new IllegalStateException("Delivery service is unavailable"))
            .when(delivery)
            .schedule(any(), any());
        var processId = startOrder(orderId, AMOUNT);

        var cancelled = runThroughRetries(processId);

        assertTrue(cancelled.state().is(OrderFulfillmentDefinition.State.CANCELLED));
        verifyCompensated(orderId);
    }

    @Test
    void failedCompensationStopsForOperator() {
        var orderId = newOrderId();
        doThrow(new IllegalStateException("Warehouse is unavailable"))
            .when(warehouse)
            .release(any());
        var processId = startOrder(orderId, TOO_MUCH);

        var failed = runThroughRetries(processId);

        // The process does not go from fallbackState to itself: a human handles the compensation
        assertEquals(ProcessStatus.FAILED, failed.status());
        assertTrue(failed.state().is(OrderFulfillmentDefinition.State.COMPENSATING));
    }

    /** Runs the process through all retries: 1, 2 and 4 seconds. */
    private ProcessInstanceDetails runThroughRetries(UUID processId) {
        var process = emulator.runUntilStop(processId);
        for (var delay : new int[]{1, 2, 4}) {
            emulator.fastForwardTime(Duration.ofSeconds(delay));
            process = emulator.runUntilStop(processId);
        }
        return process;
    }

    private void verifyCompensated(String orderId) {
        var order = inOrder(delivery, payments, warehouse);
        order.verify(delivery).cancel(orderId);
        order.verify(payments).refund(orderId);
        order.verify(warehouse).release(orderId);
    }

    private UUID startOrder(String orderId, BigDecimal amount) {
        return processEngine.newProcess(OrderFulfillmentDefinition.TYPE)
            .businessKey(orderId)
            .variable(OrderFulfillmentDefinition.ITEM, "Keyboard")
            .variable(OrderFulfillmentDefinition.AMOUNT, amount)
            .variable(OrderFulfillmentDefinition.ADDRESS, "Berlin")
            .start();
    }

    private static String newOrderId() {
        return "order-" + UUID.randomUUID();
    }
}
