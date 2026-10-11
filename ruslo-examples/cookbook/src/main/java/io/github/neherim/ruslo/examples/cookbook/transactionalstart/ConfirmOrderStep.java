package io.github.neherim.ruslo.examples.cookbook.transactionalstart;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class ConfirmOrderStep implements ProcessStep {
    private final PurchaseOrderRepository orderRepository;

    public ConfirmOrderStep(PurchaseOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /** Confirms the order: the status in the table changes in one transaction with the process transition. */
    @Override
    public void execute(ProcessContext context) {
        orderRepository.updateStatus(context.getVariable(PurchaseOrderDefinition.ORDER_ID), "CONFIRMED");
    }
}
