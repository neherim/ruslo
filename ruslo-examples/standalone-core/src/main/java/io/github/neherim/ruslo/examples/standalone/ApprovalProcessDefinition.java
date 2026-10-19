package io.github.neherim.ruslo.examples.standalone;

import io.github.neherim.ruslo.core.api.definition.*;

/**
 * Document approval without Spring: a step picks the approver, then the process waits for the decision.
 * Steps and handlers are plain methods or classes; no container is needed.
 */
public class ApprovalProcessDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("APPROVAL");
    static final long CFO_APPROVAL_THRESHOLD = 10_000;

    /**
     * Describes approver selection, waiting for the decision and two terminal outcomes.
     */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(ApprovalState.CREATED)
            .step(ApprovalState.CREATED, ApprovalProcessDefinition::selectApprover, ApprovalState.WAITING_FOR_APPROVAL)
            .await(ApprovalState.WAITING_FOR_APPROVAL, await -> await
                .message(Constants.APPROVE_MESSAGE, ApprovalProcessDefinition::onApprove, ApprovalState.APPROVED)
                .message(Constants.REJECT_MESSAGE, ApprovalProcessDefinition::onReject, ApprovalState.REJECTED))
            .terminal(ApprovalState.APPROVED)
            .terminal(ApprovalState.REJECTED)
            .build();
    }

    private static void selectApprover(ProcessContext context) {
        var amount = context.getVariable(Constants.AMOUNT);
        context.setVariable(Constants.APPROVER_ROLE, amount >= CFO_APPROVAL_THRESHOLD ? "cfo" : "manager");
    }

    private static void onApprove(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(Constants.APPROVED_BY, message.payload());
    }

    private static void onReject(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(Constants.REJECTION_REASON, message.payload());
    }
}
