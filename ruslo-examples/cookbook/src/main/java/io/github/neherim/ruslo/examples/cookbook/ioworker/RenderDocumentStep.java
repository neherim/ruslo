package io.github.neherim.ruslo.examples.cookbook.ioworker;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class RenderDocumentStep implements ProcessStep {
    private final DocumentRenderer documentRenderer;

    public RenderDocumentStep(DocumentRenderer documentRenderer) {
        this.documentRenderer = documentRenderer;
    }

    /**
     * Calls the external rendering service outside a transaction. Stores the file URL in a variable: it is
     * committed together with the process transition.
     */
    @Override
    public void execute(ProcessContext context) {
        var fileUrl = documentRenderer.renderPdf(context.getVariable(DocumentExportDefinition.DOCUMENT_ID));
        context.setVariable(DocumentExportDefinition.FILE_URL, fileUrl);
    }
}
