package io.github.neherim.ruslo.examples.cookbook.branching;

import io.github.neherim.ruslo.core.api.definition.BranchingMessageHandler;
import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

@Component
public class ReviewDecisionHandler implements BranchingMessageHandler<ReviewDecision, ReviewDecisionHandler.Verdict> {

    /** Stores the employee's decision and reports whether the application is approved. */
    @Override
    public Verdict handle(ProcessContext context, IncomingMessage<ReviewDecision> message) {
        var decision = message.payload();
        context.setVariable(LoanApplicationDefinition.REVIEWER, decision.reviewer());
        if (decision.approved()) {
            return Verdict.APPROVED;
        }
        context.setVariable(LoanApplicationDefinition.REJECTION_REASON, decision.comment());
        return Verdict.REJECTED;
    }

    /** Manual review decision. */
    public enum Verdict {
        APPROVED,
        REJECTED
    }
}
