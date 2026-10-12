package io.github.neherim.ruslo.core.definitions;

import io.github.neherim.ruslo.core.api.definition.*;

public class SimpleProcessDefinition implements ProcessDefinition {
    public final static ProcessType TYPE = ProcessType.of("SIMPLE");

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.INIT)
            .step(State.INIT, ctx -> {}, State.FINISH)
            .terminal(State.FINISH)
            .build();
    }

    public enum State implements ProcessState {
        INIT,
        FINISH
    }
}
