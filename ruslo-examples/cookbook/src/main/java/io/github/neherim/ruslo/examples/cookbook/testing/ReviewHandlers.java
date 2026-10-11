package io.github.neherim.ruslo.examples.cookbook.testing;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

/** Event handlers of the wait for a review. */
@Component
public class ReviewHandlers {

    /** Stores the reviewer who approved the document. */
    public void onApproved(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(ReviewDefinition.REVIEWER, message.payload());
    }
}
