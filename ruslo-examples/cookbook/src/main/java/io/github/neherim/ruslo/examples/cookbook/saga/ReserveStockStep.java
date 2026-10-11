package io.github.neherim.ruslo.examples.cookbook.saga;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class ReserveStockStep implements ProcessStep {
    private final Warehouse warehouse;

    public ReserveStockStep(Warehouse warehouse) {
        this.warehouse = warehouse;
    }

    /** Reserves the order item in the warehouse. */
    @Override
    public void execute(ProcessContext context) {
        warehouse.reserve(context.businessKey().orElseThrow(), context.getVariable(OrderFulfillmentDefinition.ITEM));
    }
}
