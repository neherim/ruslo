package io.github.neherim.ruslo.examples.standalone;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessType;

/**
 * Document approval without Spring: a step picks the approver, then the process waits for the decision.
 * Steps and handlers are plain lambdas or classes; no container is needed.
 */
public class ApprovalProcessDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("APPROVAL");
    static final long CFO_APPROVAL_THRESHOLD = 10_000;

    /** Describes approver selection, waiting for the decision and two terminal outcomes. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .initialState(ApprovalState.CREATED)
                .step(ApprovalState.CREATED, context -> {
                    var amount = context.getVariable(ApprovalProcess.AMOUNT);
                    context.setVariable(ApprovalProcess.APPROVER_ROLE,
                            amount >= CFO_APPROVAL_THRESHOLD ? "cfo" : "manager");
                }, ApprovalState.WAITING_FOR_APPROVAL)
                .await(ApprovalState.WAITING_FOR_APPROVAL, await -> await
                        .message(ApprovalProcess.APPROVE, (context, message) ->
                                context.setVariable(ApprovalProcess.APPROVED_BY, message.payload()),
                                ApprovalState.APPROVED)
                        .message(ApprovalProcess.REJECT, (context, message) ->
                                context.setVariable(ApprovalProcess.REJECTION_REASON, message.payload()),
                                ApprovalState.REJECTED))
                .terminal(ApprovalState.APPROVED)
                .terminal(ApprovalState.REJECTED)
                .build();
    }
}
