package io.github.neherim.ruslo.examples.cookbook.branching;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

/**
 * Recipe: branching on a business result.
 * <p>
 * Task: check a loan application. Approve a small amount at once, reject a too large one, send the rest to manual
 * review, where an employee decides.
 * <p>
 * How it works:
 * <ul>
 *   <li>{@link CheckApplicationStep} implements {@code BranchingStep<Decision>} and returns a
 *       {@link CheckApplicationStep.Decision} constant. The definition declares where each constant leads with
 *       {@code routes.on(outcome, state)}: the step knows nothing about process states.</li>
 *   <li>The employee's decision arrives as message {@link #REVIEW_DECISION}. {@link ReviewDecisionHandler}
 *       implements {@code BranchingMessageHandler<ReviewDecision, Verdict>}: the same branching, but in an
 *       await.</li>
 *   <li>Both branches store the rejection reason in variable {@link #REJECTION_REASON}, so there is a single
 *       terminal state {@link State#REJECTED}.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>A rejection is a normal business result, not an exception. An exception means a technical failure: changes
 *       are rolled back, and {@code onError} decides what happens next (recipe {@code retries}).</li>
 *   <li>The outcome is a separate enum, not {@code State}: the step reports a result, the definition picks the
 *       target. {@code on(...)} with a state constant is rejected by {@code build()}.</li>
 *   <li>A new outcome constant must be added to routes: {@code build()} checks that every constant is covered, and
 *       the engine does not start with incomplete routes.</li>
 * </ul>
 */
@Component
public class LoanApplicationDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("LOAN_APPLICATION");
    public static final VariableType<String> APPLICANT = VariableType.of("applicant", String.class);
    public static final VariableType<Long> AMOUNT = VariableType.of("amount", Long.class);
    public static final VariableType<String> REVIEWER = VariableType.of("reviewer", String.class);
    public static final VariableType<String> REJECTION_REASON = VariableType.of("rejectionReason", String.class);
    public static final MessageType<ReviewDecision> REVIEW_DECISION =
            MessageType.of("reviewDecision", ReviewDecision.class);

    private final CheckApplicationStep checkApplicationStep;
    private final ReviewDecisionHandler reviewDecisionHandler;

    public LoanApplicationDefinition(CheckApplicationStep checkApplicationStep,
                                     ReviewDecisionHandler reviewDecisionHandler) {
        this.checkApplicationStep = checkApplicationStep;
        this.reviewDecisionHandler = reviewDecisionHandler;
    }

    /** Describes the application check: an automatic decision or manual review. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .initialState(State.CHECKING)
                .step(State.CHECKING, checkApplicationStep, routes -> routes
                        .on(CheckApplicationStep.Decision.APPROVE, State.APPROVED)
                        .on(CheckApplicationStep.Decision.REJECT, State.REJECTED)
                        .on(CheckApplicationStep.Decision.MANUAL_REVIEW, State.MANUAL_REVIEW))
                .await(State.MANUAL_REVIEW, await -> await
                        .message(REVIEW_DECISION, reviewDecisionHandler, routes -> routes
                                .on(ReviewDecisionHandler.Verdict.APPROVED, State.APPROVED)
                                .on(ReviewDecisionHandler.Verdict.REJECTED, State.REJECTED)))
                .terminal(State.APPROVED)
                .terminal(State.REJECTED)
                .build();
    }

    public enum State implements ProcessState {
        CHECKING,
        MANUAL_REVIEW,
        APPROVED,
        REJECTED
    }
}
