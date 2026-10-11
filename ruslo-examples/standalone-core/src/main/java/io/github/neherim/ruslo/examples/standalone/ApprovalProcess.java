package io.github.neherim.ruslo.examples.standalone;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.VariableType;

public final class ApprovalProcess {
    public static final VariableType<String> DOCUMENT_ID = VariableType.of("documentId", String.class);
    public static final VariableType<Long> AMOUNT = VariableType.of("amount", Long.class);
    public static final VariableType<String> APPROVER_ROLE = VariableType.of("approverRole", String.class);
    public static final VariableType<String> APPROVED_BY = VariableType.of("approvedBy", String.class);
    public static final VariableType<String> REJECTION_REASON = VariableType.of("rejectionReason", String.class);
    public static final MessageType<String> APPROVE = MessageType.of("approve", String.class);
    public static final MessageType<String> REJECT = MessageType.of("reject", String.class);

    private ApprovalProcess() {
    }
}
