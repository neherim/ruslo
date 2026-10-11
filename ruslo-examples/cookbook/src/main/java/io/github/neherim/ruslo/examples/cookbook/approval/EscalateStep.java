package io.github.neherim.ruslo.examples.cookbook.approval;

import io.github.neherim.ruslo.core.api.definition.BranchingStep;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

@Component
public class EscalateStep implements BranchingStep<EscalateStep.Escalation> {
    private final ApproverDirectory directory;

    public EscalateStep(ApproverDirectory directory) {
        this.directory = directory;
    }

    /** Passes the approval to the current approver's manager, if there is one. */
    @Override
    public Escalation execute(ProcessContext context) {
        var manager = directory.managerOf(context.getVariable(ExpenseApprovalDefinition.APPROVER));
        if (manager.isEmpty()) {
            return Escalation.NO_ONE_LEFT;
        }
        context.setVariable(ExpenseApprovalDefinition.APPROVER, manager.get());
        return Escalation.ESCALATED;
    }

    /** Escalation result. */
    public enum Escalation {
        ESCALATED,
        NO_ONE_LEFT
    }
}
