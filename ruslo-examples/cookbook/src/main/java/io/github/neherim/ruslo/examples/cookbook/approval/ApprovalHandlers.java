package io.github.neherim.ruslo.examples.cookbook.approval;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

/** Event handlers of the wait for a decision. */
@Component
public class ApprovalHandlers {
    private final ApprovalNotifier notifier;

    public ApprovalHandlers(ApprovalNotifier notifier) {
        this.notifier = notifier;
    }

    /** Stores the decision and reports whether the report is approved. */
    public Outcome onDecision(ProcessContext context, IncomingMessage<Decision> message) {
        var decision = message.payload();
        context.setVariable(ExpenseApprovalDefinition.DECIDED_BY, decision.approver());
        context.setVariable(ExpenseApprovalDefinition.COMMENT, decision.comment());
        return decision.approved() ? Outcome.APPROVED : Outcome.REJECTED;
    }

    /** Reminds the current approver about the pending report. */
    public void remind(ProcessContext context) {
        notifier.remind(context.getVariable(ExpenseApprovalDefinition.APPROVER), context.businessKey().orElseThrow());
    }

    /** Decision on the report. */
    public enum Outcome {
        APPROVED,
        REJECTED
    }
}
