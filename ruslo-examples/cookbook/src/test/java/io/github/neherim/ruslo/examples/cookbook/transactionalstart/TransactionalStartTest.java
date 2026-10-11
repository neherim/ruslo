package io.github.neherim.ruslo.examples.cookbook.transactionalstart;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class TransactionalStartTest {

    @Autowired
    private PurchaseOrderService orderService;
    @Autowired
    private PurchaseOrderRepository orderRepository;
    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void savesOrderAndStartsProcess() {
        var orderId = orderService.createOrder("Keyboard", 2);

        var process = processEngine.processes().byBusinessKey(orderId).single().orElseThrow();
        assertEquals(Optional.of("NEW"), orderRepository.findStatus(orderId));

        var confirmed = emulator.runUntilStop(process.id());

        assertEquals(ProcessStatus.COMPLETED, confirmed.status());
        assertEquals(Optional.of("CONFIRMED"), orderRepository.findStatus(orderId));
    }

    @Test
    void rollbackRemovesBothOrderAndProcess() {
        // The outer transaction rolls back after createOrder: an exception in code that calls the service
        // inside its own transaction has the same effect
        var orderId = transactionTemplate.execute(status -> {
            var id = orderService.createOrder("Keyboard", 2);
            status.setRollbackOnly();
            return id;
        });

        assertTrue(orderRepository.findStatus(orderId).isEmpty());
        assertTrue(processEngine.processes().byBusinessKey(orderId).single().isEmpty());
    }
}
