package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessEngineEmulatorDiagnosticsTest extends BaseTest {
    private static final VariableType<String> ORDER_ID = VariableType.of("orderId", String.class);

    @Test
    void detailsToStringSummarizesFailedProcess() {
        ProcessDefinition definition = builder -> builder.type("FAILING")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WORK)
            .step(State.WORK, context -> {
                throw new IllegalStateException("courier unavailable");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngine(definition)) {
            var id = engine.newProcess(ProcessType.of("FAILING"))
                .businessKey("order-1")
                .variable(ORDER_ID, "o-1")
                .start();

            var failed = new ProcessEngineEmulator(engine).runUntilStop(id);

            assertEquals(ProcessStatus.FAILED, failed.status());
            assertThat(failed.toString())
                .startsWith("FAILING " + id + " FAILED in WORK, attempt 1")
                .contains("businessKey: order-1")
                .contains("lastError:   IllegalStateException: courier unavailable")
                .contains("history:     START -> WORK")
                .contains("variables:   {orderId=\"o-1\"}")
                .contains("nextRunAt:   -");
        }
    }

    @Test
    void runUntilStateReportsWhyProcessStopped() {
        ProcessDefinition definition = builder -> builder.type("FAILING")
            .initialState(State.START)
            .step(State.START, context -> {
                throw new IllegalStateException("courier unavailable");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        try (var engine = newProcessEngine(definition)) {
            var id = engine.newProcess(ProcessType.of("FAILING")).start();
            var emulator = new ProcessEngineEmulator(engine);

            var error = assertThrows(IllegalStateException.class, () -> emulator.runUntilState(id, State.DONE));

            assertThat(error.getMessage())
                .startsWith("Process stopped before reaching state DONE:\nFAILING " + id + " FAILED in START")
                .contains("courier unavailable");
        }
    }

    @Test
    void stepLimitReportsRecentHistory() {
        ProcessDefinition definition = builder -> builder.type("LOOP")
            .initialState(State.START)
            .step(State.START, context -> {}, State.WORK)
            .step(State.WORK, context -> {}, State.START)
            .build();
        try (var engine = newProcessEngine(definition)) {
            var id = engine.newProcess(ProcessType.of("LOOP")).start();
            var emulator = new ProcessEngineEmulator(engine, 20);

            var error = assertThrows(IllegalStateException.class, () -> emulator.runUntilStop(id));

            assertThat(error.getMessage())
                .startsWith("Process did not stop after 20 steps")
                .contains("history:     (11 earlier) -> WORK -> START -> WORK");
        }
    }

    private ProcessEngine newProcessEngine(ProcessDefinition... definitions) {
        return new ProcessEngineBuilder(database)
            .addProcessDefinitions(List.of(definitions))
            .withDispatcherEnabled(false)
            .withDatabaseMigrationEnabled(false)
            .build()
            .start();
    }

    private enum State implements ProcessState {
        START,
        WORK,
        DONE
    }
}
