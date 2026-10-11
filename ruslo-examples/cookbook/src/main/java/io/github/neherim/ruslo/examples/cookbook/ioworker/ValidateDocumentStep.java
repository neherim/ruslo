package io.github.neherim.ruslo.examples.cookbook.ioworker;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ValidateDocumentStep implements ProcessStep {
    private static final Logger log = LoggerFactory.getLogger(ValidateDocumentStep.class);

    /** Validates the document in a transaction: application DB work belongs here. */
    @Override
    public void execute(ProcessContext context) {
        log.info("Validating document {}", context.getVariable(DocumentExportDefinition.DOCUMENT_ID));
    }
}
