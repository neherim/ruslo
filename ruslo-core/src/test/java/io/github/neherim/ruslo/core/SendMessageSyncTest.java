package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** Synchronous message handling in the caller's thread and transaction. */
class SendMessageSyncTest extends BaseTest {
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);
    private static final MessageType<String> NOTE = MessageType.of("NOTE", String.class);
    private static final VariableType<String> SIGNAL_PAYLOAD = VariableType.of("signal", String.class);
    private static final ProcessType TYPE = ProcessType.of("HANDLE_MESSAGE");
    private final List<String> log = new ArrayList<>();
    private final AtomicBoolean fail = new AtomicBoolean();
    private ProcessEngine engine;

    @BeforeEach
    void setUp() {
        ProcessDefinition definition = builder -> builder.type(TYPE.name())
            .onError(ProcessErrorHandler.fixed(3, Duration.ofHours(1)))
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await
                .message(SIGNAL, (context, message) -> {
                    log.add(message.payload());
                    context.setVariable(SIGNAL_PAYLOAD, message.payload());
                    if (fail.get()) throw new IllegalStateException("boom");
                }, State.NEXT)
                .message(NOTE, (context, message) -> log.add("note:" + message.payload()), State.WAIT))
            .step(State.NEXT, context -> log.add("next"), State.DONE)
            .terminal(State.DONE)
            .build();
        engine = newProcessEngineWith(definition);
    }

    @AfterEach
    void tearDown() {
        engine.close();
    }

    @Test
    void handlesMessageImmediatelyAndLeavesNextStepsToDispatcher() {
        var id = engine.newProcess(TYPE).start();

        engine.sendMessageSync(id, SIGNAL, "m");

        var process = find(id);
        assertEquals("NEXT", process.state().name());
        assertEquals(Optional.of("m"), process.variables().get(SIGNAL_PAYLOAD));
        assertEquals(List.of("m"), log);
    }

    @Test
    void selfTargetKeepsQueuedMessages() {
        var id = engine.newProcess(TYPE).start();
        engine.sendMessage(id, NOTE, "queued");

        engine.sendMessageSync(id, NOTE, "sync");

        assertEquals("WAIT", find(id).state().name());
        assertEquals(List.of("note:sync"), log);
        emulator(engine).runOneStep(id);
        assertEquals(List.of("note:sync", "note:queued"), log);
    }

    @Test
    void handlesMessageOutOfQueueOrder() {
        var id = engine.newProcess(TYPE).start();
        engine.sendMessage(id, NOTE, "queued");

        engine.sendMessageSync(id, SIGNAL, "m");

        assertEquals("NEXT", find(id).state().name());
        assertEquals(List.of("m"), log);
    }

    @Test
    void rejectsUnexpectedMessageAndCompletedProcess() {
        var id = engine.newProcess(TYPE).start();
        engine.sendMessageSync(id, SIGNAL, "m");

        var unexpected = assertThrows(MessageNotExpectedException.class,
                () -> engine.sendMessageSync(id, SIGNAL, "again"));
        assertEquals(ProcessState.of("NEXT"), unexpected.getCurrentState());
        assertEquals(ProcessStatus.ACTIVE, unexpected.getStatus());
        emulator(engine).runUntilStop(id);
        var completed = assertThrows(MessageNotExpectedException.class,
                () -> engine.sendMessageSync(id, SIGNAL, "late"));
        assertEquals(ProcessState.of("DONE"), completed.getCurrentState());
        assertEquals(ProcessStatus.COMPLETED, completed.getStatus());
        assertEquals(List.of("m", "next"), log);
    }

    @Test
    void handlerErrorIsRethrownAndNothingIsSaved() {
        var id = engine.newProcess(TYPE).start();
        fail.set(true);

        var error = assertThrows(IllegalStateException.class, () -> engine.sendMessageSync(id, SIGNAL, "m"));

        assertEquals("boom", error.getMessage());
        var process = find(id);
        assertEquals("WAIT", process.state().name());
        assertEquals(ProcessStatus.ACTIVE, process.status());
        assertEquals(0, process.attempt());
        assertEquals("[]", messages(id));
    }

    @Test
    void selfTargetKeepsRetryBackoffOfQueuedMessage() {
        var id = engine.newProcess(TYPE).start();
        fail.set(true);
        engine.sendMessage(id, SIGNAL, "queued");
        emulator(engine).runOneStep(id);
        var failed = find(id);
        var availableAt = availableAt(id);
        assertEquals(1, failed.attempt());
        fail.set(false);

        engine.sendMessageSync(id, NOTE, "sync");

        var process = find(id);
        assertEquals("WAIT", process.state().name());
        assertEquals(1, process.attempt());
        assertEquals(failed.lastError(), process.lastError());
        assertEquals(availableAt, availableAt(id));
    }

    @Test
    void joinsCallerTransaction() {
        var id = engine.newProcess(TYPE).start();

        assertThrows(IllegalArgumentException.class, () -> database.doInTransaction(() -> {
            engine.sendMessageSync(id, SIGNAL, "m");
            assertEquals("NEXT", find(id).state().name());
            throw new IllegalArgumentException("caller rollback");
        }));

        assertEquals("WAIT", find(id).state().name());
        assertEquals("[]", messages(id));
    }

    private ProcessInstanceDetails find(UUID id) {
        return engine.processes().byId(id).singleDetails().orElseThrow();
    }

    private String availableAt(UUID id) {
        return database.queryForObject("select available_at from xs_process_instance where id = ?",
                rs -> rs.getString(1), id)
            .orElseThrow();
    }

    private String messages(UUID id) {
        return database.queryForObject("select messages from xs_process_instance where id = ?",
                rs -> rs.getString(1), id)
            .orElseThrow();
    }

    private enum State implements ProcessState {
        WAIT,
        NEXT,
        DONE
    }
}
