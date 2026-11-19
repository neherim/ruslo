package io.github.neherim.ruslo.examples.quickstart.steps;

import io.github.neherim.ruslo.core.api.definition.LinearAction;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.examples.quickstart.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CreateOrderStep implements LinearAction {
    private static final Logger log = LoggerFactory.getLogger(CreateOrderStep.class);

    /** Creates the order; the process then waits for payment. */
    @Override
    public void execute(ProcessContext ctx) {
        var customerId = ctx.getVariable(Constants.CUSTOMER_ID);
        log.info("Creating order for customer {}", customerId);
    }
}
