package io.github.neherim.ruslo.examples.cookbook.testing;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Recipe: process testing techniques.
 * <p>
 * The process itself is deliberately simple, a document review: preparation, waiting for the reviewer's decision,
 * publication or expiry. The point of the recipe is the tests next to it, in {@code src/test}:
 * <ul>
 *   <li>{@code ReviewHandlersTest}: a handler unit test with {@code TestProcessContext}, without Spring or a
 *       database.</li>
 *   <li>{@code EmulatorTest}: step-by-step execution ({@code runOneStep}, {@code runUntilState}), message delivery
 *       and time control ({@code fastForwardTime}, {@code setTime}).</li>
 *   <li>{@code DispatcherEnabledTest}: a test with a running dispatcher: background workers run the steps, as in
 *       production, while the emulator still controls time.</li>
 * </ul>
 */
@Component
public class ReviewDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("REVIEW");
    public static final Duration REVIEW_DEADLINE = Duration.ofDays(3);
    public static final VariableType<String> DOCUMENT_ID = VariableType.of("documentId", String.class);
    public static final VariableType<String> REVIEWER = VariableType.of("reviewer", String.class);
    public static final MessageType<String> APPROVED = MessageType.of("approved", String.class);

    private final PrepareReviewStep prepareReviewStep;
    private final ReviewHandlers reviewHandlers;

    public ReviewDefinition(PrepareReviewStep prepareReviewStep, ReviewHandlers reviewHandlers) {
        this.prepareReviewStep = prepareReviewStep;
        this.reviewHandlers = reviewHandlers;
    }

    /** Describes the review: the reviewer's decision publishes the document, the deadline closes the review. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .initialState(State.PREPARING)
                .step(State.PREPARING, prepareReviewStep, State.WAITING_FOR_REVIEW)
                .await(State.WAITING_FOR_REVIEW, await -> await
                        .message(APPROVED, reviewHandlers::onApproved, State.PUBLISHED)
                        .timeout(REVIEW_DEADLINE, State.EXPIRED))
                .terminal(State.PUBLISHED)
                .terminal(State.EXPIRED)
                .build();
    }

    public enum State implements ProcessState {
        PREPARING,
        WAITING_FOR_REVIEW,
        PUBLISHED,
        EXPIRED
    }
}
