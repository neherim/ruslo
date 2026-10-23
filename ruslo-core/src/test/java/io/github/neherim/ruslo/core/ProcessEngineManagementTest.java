package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.api.view.ProcessTypeCounts;
import io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProcessEngineManagementTest extends BaseTest {
    @Test
    void exposesCountersAndDefinitionMetadata() {
        var engine = newProcessEngineWith(new SimpleProcessDefinition());
        try {
            engine.newProcess(SimpleProcessDefinition.TYPE).start();
            assertEquals(1, new ProcessEngineAdmin(engine).countIncompleteProcesses());
            assertEquals(2, new ProcessEngineAdmin(engine).listProcessSpecifications().getFirst().states().size());
        } finally {
            engine.close();
        }
    }

    @Test
    void countsIncompleteProcessesByType() {
        var engine = newProcessEngineWith(new SimpleProcessDefinition());
        try {
            var admin = new ProcessEngineAdmin(engine);
            var now = Instant.now();
            engine.newProcess(SimpleProcessDefinition.TYPE).start();
            var failed = engine.newProcess(SimpleProcessDefinition.TYPE).start();
            database.update("update xs_process_instance set status = 'FAILED' where id = ?", failed);

            assertEquals(List.of(new ProcessTypeCounts(SimpleProcessDefinition.TYPE.id(), 1, 1, 1)),
                    admin.countIncompleteProcessesByType(now.plusSeconds(60)));
            assertEquals(0, admin.countIncompleteProcessesByType(now.minusSeconds(60)).getFirst().overdue());
            assertEquals(2, new ProcessEngineAdmin(engine).listProcessSpecifications().getFirst().states().size());
        } finally {
            engine.close();
        }
    }

    @Test
    void manualMoveActivatesTargetBehaviorAndTerminal() {
        var type = ProcessType.of("MANAGEMENT");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.after(Duration.ofSeconds(20), State.DONE))
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(type).start();
            var priority = VariableType.of("priority", String.class);
            new ProcessEngineAdmin(engine).setProcessVariable(id, priority, "high");
            var variables = engine.processes().byId(id).singleDetails().orElseThrow().variables();
            assertEquals(Optional.of("high"), variables.get(priority));
            assertEquals(Optional.empty(), variables.get(VariableType.of("missing", String.class)));
            new ProcessEngineAdmin(engine).moveProcessToState(id, State.WAIT);
            var waiting = engine.processes().byId(id).singleDetails().orElseThrow();
            assertEquals(ProcessStatus.ACTIVE, waiting.status());
            assertTrue(waiting.nextRunAt().isPresent());
            new ProcessEngineAdmin(engine).moveProcessToState(id, State.DONE);
            assertEquals(ProcessStatus.COMPLETED,
                    engine.processes().byId(id).singleDetails().orElseThrow().status());
            assertThrows(RuntimeException.class,
                    () -> new ProcessEngineAdmin(engine).moveProcessToState(id, State.START));
        } finally {
            engine.close();
        }
    }

    @Test
    void overdueCountsDueAwaitTimerAndUnhandledMessage() {
        var signal = MessageType.of("SIGNAL", String.class);
        var withAfter = ProcessType.of("OVERDUE_AFTER");
        var withMessage = ProcessType.of("OVERDUE_MESSAGE");
        var engine = newProcessEngineWith(
                builder -> builder.type(withAfter)
                    .initialState(State.WAIT)
                    .await(State.WAIT, await -> await.after(Duration.ofHours(1), State.DONE))
                    .terminal(State.DONE)
                    .build(),
                builder -> builder.type(withMessage)
                    .initialState(State.WAIT)
                    .await(State.WAIT, await -> await.message(signal, (context, message) -> {}, State.DONE))
                    .terminal(State.DONE)
                    .build());
        try {
            var admin = new ProcessEngineAdmin(engine);
            var now = Instant.now();
            engine.newProcess(withAfter).start();
            var waiting = engine.newProcess(withMessage).start();
            assertEquals(List.of(new ProcessTypeCounts(withAfter.id(), 1, 0, 0),
                    new ProcessTypeCounts(withMessage.id(), 1, 0, 0)),
                    admin.countIncompleteProcessesByType(now.plus(Duration.ofMinutes(59))));
            engine.sendMessage(waiting, signal, "unhandled");
            assertEquals(List.of(new ProcessTypeCounts(withAfter.id(), 1, 0, 1),
                    new ProcessTypeCounts(withMessage.id(), 1, 0, 1)),
                    admin.countIncompleteProcessesByType(now.plus(Duration.ofHours(2))));
        } finally {
            engine.close();
        }
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        DONE
    }
}
