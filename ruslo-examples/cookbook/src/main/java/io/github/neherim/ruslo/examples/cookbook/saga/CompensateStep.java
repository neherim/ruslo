package io.github.neherim.ruslo.examples.cookbook.saga;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class CompensateStep implements ProcessStep {
    private final Warehouse warehouse;
    private final Payments payments;
    private final Delivery delivery;

    public CompensateStep(Warehouse warehouse, Payments payments, Delivery delivery) {
        this.warehouse = warehouse;
        this.payments = payments;
        this.delivery = delivery;
    }

    /**
     * Undoes everything that could have been done for the order, in reverse order. The step does not need to know
     * where the process failed: each undo is idempotent and does nothing if there is nothing to undo.
     */
    @Override
    public void execute(ProcessContext context) {
        var orderId = context.businessKey().orElseThrow();
        delivery.cancel(orderId);
        payments.refund(orderId);
        warehouse.release(orderId);
    }
}
