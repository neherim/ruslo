package io.github.neherim.ruslo.examples.cookbook.saga;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class ScheduleDeliveryStep implements ProcessStep {
    private final Delivery delivery;

    public ScheduleDeliveryStep(Delivery delivery) {
        this.delivery = delivery;
    }

    /** Schedules the order delivery. */
    @Override
    public void execute(ProcessContext context) {
        delivery.schedule(context.businessKey().orElseThrow(), context.getVariable(OrderFulfillmentDefinition.ADDRESS));
    }
}
