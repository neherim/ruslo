package io.github.neherim.ruslo.examples.cookbook.testing;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.test.TestProcessContext;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Handler unit test without Spring, the engine or a database. {@link TestProcessContext} stores variables as JSON,
 * like the engine, so the test catches a value that does not survive serialization.
 */
class ReviewHandlersTest {
    private final ReviewHandlers reviewHandlers = new ReviewHandlers();

    @Test
    void storesReviewerOfApprovedDocument() {
        var context = TestProcessContext.builder()
            .state(ReviewDefinition.State.WAITING_FOR_REVIEW)
            .variable(ReviewDefinition.DOCUMENT_ID, "doc-1")
            .build();
        var message = new IncomingMessage<>(UUID.randomUUID(), ReviewDefinition.APPROVED, "alice", Instant.now());

        reviewHandlers.onApproved(context, message);

        assertEquals("alice", context.getVariable(ReviewDefinition.REVIEWER));
        // The handler wrote no extra variables
        assertEquals(Set.of("documentId", "reviewer"), context.variableNames());
    }
}
