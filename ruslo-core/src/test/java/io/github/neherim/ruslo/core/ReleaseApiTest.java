package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.definition.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReleaseApiTest extends BaseTest {
    private static final ProcessType TYPE = ProcessType.of("CLEANUP");

    private enum State implements ProcessState {
        START,
        DONE
    }

    private ProcessDefinition definition() {
        return builder -> builder.type(TYPE)
            .initialState(State.START)
            .step(State.START, context -> {}, State.DONE)
            .terminal(State.DONE)
            .build();
    }

    @Test
    void adminRecoversRemovedAwaitStateAndDiscardsOldSignals() {
        var messageType = MessageType.of("signal", String.class);
        var waiting = ProcessState.of("WAITING");
        ProcessDefinition original = builder -> builder.type(TYPE)
            .initialState(waiting)
            .await(waiting, await -> await.message(messageType, (context, message) -> {}, State.DONE))
            .terminal(State.DONE)
            .build();
        java.util.UUID id;
        try (var engine = newProcessEngineWith(original)) {
            id = engine.newProcess(TYPE).start();
            engine.sendMessage(id, messageType, "old signal");
        }
        try (var engine = newProcessEngineWith(definition())) {
            assertTrue(engine.processes().byId(id).singleDetails().isPresent());
            assertThrows(io.github.neherim.ruslo.core.api.exception.UnsupportedProcessStateException.class,
                    () -> emulator(engine).runOneStep(id));
            new ProcessEngineAdmin(engine).moveProcessToState(id, State.START);
            assertEquals("[]", database.queryForObject("select messages from xs_process_instance where id = ?",
                    rs -> rs.getString(1), id)
                .orElseThrow());
            assertEquals(State.DONE.id(), emulator(engine).runUntilStop(id).state());
        }
    }
}
