package io.github.neherim.ruslo.examples.cookbook.cancellation;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class CancellationTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @Autowired
    private OrderCancellation cancellation;
    @MockitoSpyBean
    private OrderWarehouse warehouse;
    @MockitoSpyBean
    private OrderPayments payments;

    @Test
    void cancelsWhileWaitingForPayment() {
        var orderId = newOrderId();
        var processId = startOrder(orderId);
        emulator.runUntilStop(processId);

        assertEquals(OrderCancellation.Result.ACCEPTED, cancellation.cancel(orderId, "Changed my mind"));
        // Handled synchronously: the process is already in CANCELLING, the dispatcher runs the cancel step
        assertTrue(stateOf(processId).is(OrderDeliveryDefinition.State.CANCELLING));
        var cancelled = emulator.runUntilStop(processId);

        assertTrue(cancelled.state().is(OrderDeliveryDefinition.State.CANCELLED));
        verify(warehouse).release(orderId);
        verify(payments).refund(orderId);
    }

    @Test
    void cancelsPackedOrder() {
        var orderId = newOrderId();
        var processId = startOrder(orderId);
        emulator.runUntilStop(processId);
        var packed = emulator.sendMessageAndRunUntilStop(
                processId, OrderDeliveryDefinition.PAYMENT_RECEIVED, "payment-1");
        assertTrue(packed.state().is(OrderDeliveryDefinition.State.WAITING_FOR_COURIER));

        assertEquals(OrderCancellation.Result.ACCEPTED, cancellation.cancel(orderId, "Found it cheaper"));
        var cancelled = emulator.runUntilStop(processId);

        assertTrue(cancelled.state().is(OrderDeliveryDefinition.State.CANCELLED));
        verify(payments).refund(orderId);
    }

    @Test
    void asksToRetryWhileStepIsRunning() {
        var orderId = newOrderId();
        var processId = startOrder(orderId);

        // The reservation step has not run yet: the process is not in an await, nothing can accept the cancellation
        assertEquals(OrderCancellation.Result.BUSY, cancellation.cancel(orderId, "Changed my mind"));

        emulator.runUntilStop(processId);
        assertEquals(OrderCancellation.Result.ACCEPTED, cancellation.cancel(orderId, "Changed my mind"));
    }

    @Test
    void refusesShippedOrder() {
        var orderId = newOrderId();
        var processId = startOrder(orderId);
        emulator.runUntilStop(processId);
        emulator.sendMessageAndRunUntilStop(processId, OrderDeliveryDefinition.PAYMENT_RECEIVED, "payment-1");
        var shipped = emulator.sendMessageAndRunUntilStop(
                processId, OrderDeliveryDefinition.HANDED_TO_COURIER, "courier-7");
        assertTrue(shipped.state().is(OrderDeliveryDefinition.State.SHIPPED));

        assertEquals(OrderCancellation.Result.TOO_LATE, cancellation.cancel(orderId, "Changed my mind"));
        verify(payments, never()).refund(any());
    }

    @Test
    void refundsPaymentQueuedBeforeCancellation() {
        var orderId = newOrderId();
        var processId = startOrder(orderId);
        emulator.runUntilStop(processId);

        // The payment is accepted but not yet handled when the cancellation arrives
        processEngine.sendMessage(processId, OrderDeliveryDefinition.PAYMENT_RECEIVED, "payment-1");
        assertEquals(OrderCancellation.Result.ACCEPTED, cancellation.cancel(orderId, "Changed my mind"));
        var cancelled = emulator.runUntilStop(processId);

        // The payment handler did not run, but the refund is keyed by the order, not by a process variable
        assertTrue(cancelled.state().is(OrderDeliveryDefinition.State.CANCELLED));
        assertTrue(cancelled.variables().get(OrderDeliveryDefinition.PAYMENT_ID).isEmpty());
        verify(warehouse, never()).pack(any());
        verify(payments).refund(orderId);
    }

    private UUID startOrder(String orderId) {
        return processEngine.newProcess(OrderDeliveryDefinition.TYPE)
                .businessKey(orderId)
                .start();
    }

    private ProcessStateId stateOf(UUID processId) {
        return processEngine.processes().byId(processId).single().orElseThrow().state();
    }

    private static String newOrderId() {
        return "order-" + UUID.randomUUID();
    }
}
