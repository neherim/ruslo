package io.github.neherim.ruslo.perf.definition;

import io.github.neherim.ruslo.core.api.WorkerSpec.ExecutionMode;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;

/** Five fast steps, each transition committed; measures execution throughput. */
public final class FastProcessDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("PERF_EXECUTION");
    private final ProcessType type;
    private final WorkerId workerId;

    /** Creates the definition for the default transactional worker. */
    public FastProcessDefinition() {
        this(TYPE, WorkerId.DEFAULT);
    }

    /** Creates the definition for the given execution mode. */
    public FastProcessDefinition(ExecutionMode executionMode) {
        this(ProcessType.of("PERF_EXECUTION_" + executionMode.name()),
                switch (executionMode) {
                    case TRANSACTIONAL -> WorkerId.DEFAULT;
                    case NON_TRANSACTIONAL -> new WorkerId("perf-non-transactional");
                });
    }

    private FastProcessDefinition(ProcessType type, WorkerId workerId) {
        this.type = type;
        this.workerId = workerId;
    }

    public ProcessType type() {
        return type;
    }

    /** Declares five empty steps and a terminal state to measure engine overhead. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder.type(type)
            .defaultWorker(workerId)
            .initialState(State.STEP_1)
            .step(State.STEP_1, context -> {}, State.STEP_2)
            .step(State.STEP_2, context -> {}, State.STEP_3)
            .step(State.STEP_3, context -> {}, State.STEP_4)
            .step(State.STEP_4, context -> {}, State.STEP_5)
            .step(State.STEP_5, context -> {}, State.DONE)
            .terminal(State.DONE)
            .build();
    }

    public enum State implements ProcessState {
        STEP_1,
        STEP_2,
        STEP_3,
        STEP_4,
        STEP_5,
        DONE
    }
}
