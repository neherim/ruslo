package io.github.neherim.ruslo.examples.quickstart;

import io.github.neherim.ruslo.core.api.definition.ProcessState;

public enum OrderState implements ProcessState {
    CREATED,
    WAITING_FOR_PAYMENT,
    DELIVERING,
    CANCELLED,
    COMPLETED
}
