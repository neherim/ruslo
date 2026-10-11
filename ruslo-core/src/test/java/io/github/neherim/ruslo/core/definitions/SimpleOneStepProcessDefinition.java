package io.github.neherim.ruslo.core.definitions;

import io.github.neherim.ruslo.core.api.definition.*;

public class SimpleOneStepProcessDefinition implements ProcessDefinition {
    public static final ProcessState INITIAL_STATE = ProcessState.of("EXECUTION");
    public static final ProcessState FINAL_STATE = ProcessState.of("COMPLETED");

    public ProcessType processType() { return ProcessType.of("simple_one_step"); }

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(processType()).initialState(INITIAL_STATE)
                .step(INITIAL_STATE, context -> {}, FINAL_STATE)
                .terminal(FINAL_STATE).build();
    }
}
