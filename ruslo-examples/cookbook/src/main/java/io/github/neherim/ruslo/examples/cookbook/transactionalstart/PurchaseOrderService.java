package io.github.neherim.ruslo.examples.cookbook.transactionalstart;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PurchaseOrderService {
    private final PurchaseOrderRepository orderRepository;
    private final ProcessEngine processEngine;

    public PurchaseOrderService(PurchaseOrderRepository orderRepository, ProcessEngine processEngine) {
        this.orderRepository = orderRepository;
        this.processEngine = processEngine;
    }

    /**
     * Saves the order and starts its process in one transaction: on rollback neither remains.
     *
     * @return order id, also the process business key
     */
    @Transactional
    public String createOrder(String item, int quantity) {
        var orderId = UUID.randomUUID().toString();
        orderRepository.insert(orderId, item, quantity, "NEW");
        processEngine.newProcess(PurchaseOrderDefinition.TYPE)
            .businessKey(orderId)
            .variable(PurchaseOrderDefinition.ORDER_ID, orderId)
            .start();
        return orderId;
    }
}
