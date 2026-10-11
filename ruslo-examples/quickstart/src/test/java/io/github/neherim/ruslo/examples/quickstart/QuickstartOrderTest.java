package io.github.neherim.ruslo.examples.quickstart;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = Main.class)
class QuickstartOrderTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void completesOrderAfterPaymentMessage() {
        var processId = orderService.createOrder("customer-1");

        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(OrderState.WAITING_FOR_PAYMENT));

        orderService.confirmPayment(processId, "txn-42");

        var completed = emulator.runUntilStop(processId);
        assertTrue(completed.state().is(OrderState.COMPLETED));
    }

    @Test
    void cancelsOrderAfterCancelMessage() {
        var processId = orderService.createOrder("customer-2");

        emulator.runUntilStop(processId);
        orderService.cancelOrder(processId, "customer request");

        var cancelled = emulator.runUntilStop(processId);
        assertTrue(cancelled.state().is(OrderState.CANCELLED));
        assertEquals("customer request",
                cancelled.variables().get(Constants.CANCELLATION_REASON).orElseThrow());
    }
}
