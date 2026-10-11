package io.github.neherim.ruslo.examples.cookbook.approval;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessStep;
import org.springframework.stereotype.Component;

@Component
public class RequestApprovalStep implements ProcessStep {
    private final ApprovalNotifier notifier;

    public RequestApprovalStep(ApprovalNotifier notifier) {
        this.notifier = notifier;
    }

    /** Asks the current approver for a decision; after this step the deadline starts over. */
    @Override
    public void execute(ProcessContext context) {
        notifier.requestApproval(
                context.getVariable(ExpenseApprovalDefinition.APPROVER), context.businessKey().orElseThrow());
    }
}
