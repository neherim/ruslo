package io.github.neherim.ruslo.examples.cookbook.cancellation;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class PackOrderStep implements ProcessStep {
    private final OrderWarehouse warehouse;

    public PackOrderStep(OrderWarehouse warehouse) {
        this.warehouse = warehouse;
    }

    /** Packs the paid order. */
    @Override
    public void execute(ProcessContext context) {
        warehouse.pack(context.businessKey().orElseThrow());
    }
}
