package io.github.neherim.ruslo.examples.cookbook.testing;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class PrepareReviewStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(PrepareReviewStep.class);

    /** Prepares the document for review. */
    @Override
    public void execute(ProcessContext context) {
        log.info("Preparing document {} for review", context.getVariable(ReviewDefinition.DOCUMENT_ID));
    }
}
