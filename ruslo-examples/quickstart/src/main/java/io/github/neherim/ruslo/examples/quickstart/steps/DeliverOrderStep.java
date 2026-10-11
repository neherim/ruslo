package io.github.neherim.ruslo.examples.quickstart.steps;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DeliverOrderStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(DeliverOrderStep.class);

    /** Delivers the order; the process then completes. */
    @Override
    public void execute(ProcessContext context) {
        log.info("Delivering order for process {}", context.processId());
    }
}
