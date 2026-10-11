package io.github.neherim.ruslo.examples.standalone;

import io.github.neherim.ruslo.core.api.definition.ProcessState;

public enum ApprovalState implements ProcessState {
    CREATED,
    WAITING_FOR_APPROVAL,
    APPROVED,
    REJECTED
}
