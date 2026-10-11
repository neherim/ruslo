package io.github.neherim.ruslo.examples.cookbook.cancellation;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class ReserveItemsStep implements ProcessStep {
    private final OrderWarehouse warehouse;

    public ReserveItemsStep(OrderWarehouse warehouse) {
        this.warehouse = warehouse;
    }

    /** Reserves the order items before payment. */
    @Override
    public void execute(ProcessContext context) {
        warehouse.reserve(context.businessKey().orElseThrow());
    }
}
