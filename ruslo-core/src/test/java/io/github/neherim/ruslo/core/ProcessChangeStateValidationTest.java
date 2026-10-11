package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.exception.UnsupportedProcessStateException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessChangeStateValidationTest extends BaseTest {

    enum State implements ProcessState {
        INIT, REGISTERED, UNREGISTERED
    }

    /**
     * Checks that a transition to an unregistered state throws {@link UnsupportedProcessStateException}.
     */
    @Test
    void shouldThrowWhenTransitioningToUnregisteredState() {
        var type = ProcessType.of("VALIDATION_TEST");
        var processEngine = newProcessEngineWith(builder -> builder
                .type(type)
                .initialState(State.INIT)
                .step(State.INIT, ctx -> { }, State.REGISTERED)
                .terminal(State.REGISTERED)
                .build());

        var processId = processEngine.newProcess(type).start();

        assertThrows(UnsupportedProcessStateException.class,
                () -> new ProcessEngineAdmin(processEngine).moveProcessToState(processId, State.UNREGISTERED));
    }
}
