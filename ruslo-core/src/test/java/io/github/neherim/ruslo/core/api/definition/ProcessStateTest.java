package io.github.neherim.ruslo.core.api.definition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessStateTest {

    private enum State implements ProcessState {
        INIT
    }

    @Test
    void enumStateShouldReturnCanonicalId() {
        assertEquals(new ProcessStateId("INIT"), State.INIT.id());
    }
}
