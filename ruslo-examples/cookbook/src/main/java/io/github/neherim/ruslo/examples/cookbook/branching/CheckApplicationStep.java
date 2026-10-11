package io.github.neherim.ruslo.examples.cookbook.branching;

import io.github.neherim.ruslo.core.api.definition.BranchingStep;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

@Component
public class CheckApplicationStep implements BranchingStep<CheckApplicationStep.Decision> {
    static final long AUTO_APPROVE_LIMIT = 1_000;
    static final long MAX_AMOUNT = 100_000;

    /**
     * Decides automatically by the requested amount: approves a small amount, rejects a too large one with
     * a reason, sends the rest to manual review.
     */
    @Override
    public Decision execute(ProcessContext context) {
        long amount = context.getVariable(LoanApplicationDefinition.AMOUNT);
        if (amount <= AUTO_APPROVE_LIMIT) {
            return Decision.APPROVE;
        }
        if (amount > MAX_AMOUNT) {
            context.setVariable(LoanApplicationDefinition.REJECTION_REASON, "Amount exceeds " + MAX_AMOUNT);
            return Decision.REJECT;
        }
        return Decision.MANUAL_REVIEW;
    }

    /** Result of the automatic check. */
    public enum Decision {
        APPROVE,
        REJECT,
        MANUAL_REVIEW
    }
}
