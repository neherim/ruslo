package io.github.neherim.ruslo.examples.cookbook.cancellation;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class CancelOrderStep implements ProcessStep {
    private final OrderWarehouse warehouse;
    private final OrderPayments payments;

    public CancelOrderStep(OrderWarehouse warehouse, OrderPayments payments) {
        this.warehouse = warehouse;
        this.payments = payments;
    }

    /**
     * Releases the reservation and refunds the payment by order id. Does not depend on the stage at which the
     * cancellation arrived: both operations do nothing if there is no reservation or payment.
     */
    @Override
    public void execute(ProcessContext context) {
        var orderId = context.businessKey().orElseThrow();
        warehouse.release(orderId);
        payments.refund(orderId);
    }
}
