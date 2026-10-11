package io.github.neherim.ruslo.examples.cookbook.ioworker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** External rendering service stub. A real application makes a long HTTP call here. */
@Component
public class DocumentRenderer {
    private static final Logger log = LoggerFactory.getLogger(DocumentRenderer.class);

    /**
     * Renders the document PDF.
     *
     * @return URL of the rendered file
     */
    public String renderPdf(String documentId) {
        log.info("Rendering document {}", documentId);
        return "https://files.example.com/" + documentId + ".pdf";
    }
}
