package io.github.neherim.ruslo.examples.quickstart.steps;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import io.github.neherim.ruslo.examples.quickstart.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CreateOrderStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(CreateOrderStep.class);

    /** Creates the order; the process then waits for payment. */
    @Override
    public void execute(ProcessContext context) {
        var customerId = context.getVariable(Constants.CUSTOMER_ID);
        log.info("Creating order for customer {}", customerId);
    }
}
