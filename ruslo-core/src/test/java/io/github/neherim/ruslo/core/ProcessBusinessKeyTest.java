package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessNotFoundException;
import io.github.neherim.ruslo.core.api.exception.TooManyRowsException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProcessBusinessKeyTest extends BaseTest {
    private static final ProcessType PAYMENT = ProcessType.of("PAYMENT");
    private static final ProcessType DELIVERY = ProcessType.of("DELIVERY");
    private static final String ORDER = "order-42";
    private static final MessageType<String> SIGNAL = MessageType.of("signal", String.class);

    @Test
    void sendsToTheMatchingTypeAndKey() {
        try (var engine = newProcessEngineWith(definition(PAYMENT), definition(DELIVERY))) {
            var payment = engine.newProcess(PAYMENT).businessKey(ORDER).start();
            var otherOrder = engine.newProcess(PAYMENT).businessKey("order-43").start();
            var delivery = engine.newProcess(DELIVERY).businessKey(ORDER).start();

            engine.sendMessage(PAYMENT, ORDER, SIGNAL, "received");

            assertEquals(ProcessStatus.ACTIVE, engine.processes().byId(payment).single().orElseThrow().status());
            var emulator = emulator(engine);
            assertEquals(ProcessStatus.COMPLETED, emulator.runUntilStop(payment).status());
            assertEquals(ProcessStatus.ACTIVE, emulator.runUntilStop(otherOrder).status());
            assertEquals(ProcessStatus.ACTIVE, emulator.runUntilStop(delivery).status());
        }
    }

    @Test
    void handlesMessageSynchronouslyAndFindsCompletedProcess() {
        try (var engine = newProcessEngineWith(definition(PAYMENT))) {
            var id = engine.newProcess(PAYMENT).businessKey(ORDER).start();

            engine.sendMessageSync(PAYMENT, ORDER, SIGNAL, "received");

            assertEquals(ProcessStatus.COMPLETED, engine.processes().byId(id).single().orElseThrow().status());
            var rejection = assertThrows(MessageNotExpectedException.class,
                    () -> engine.sendMessage(PAYMENT, ORDER, SIGNAL, "late"));
            assertEquals(ProcessStatus.COMPLETED, rejection.getStatus());
        }
    }

    @Test
    void resumesAtTheCurrentOrSpecifiedState() {
        try (var engine = newProcessEngineWith(definition(PAYMENT))) {
            var id = engine.newProcess(PAYMENT).businessKey(ORDER).start();
            var emulator = emulator(engine);
            engine.sendMessage(PAYMENT, ORDER, SIGNAL, "fail");
            assertEquals(ProcessStatus.FAILED, emulator.runOneStep(id).status());

            engine.resumeFailedProcess(PAYMENT, ORDER);

            var resumed = engine.processes().byId(id).single().orElseThrow();
            assertEquals(State.WAIT, resumed.state());
            assertEquals(ProcessStatus.ACTIVE, resumed.status());
            assertEquals(ProcessStatus.FAILED, emulator.runOneStep(id).status());

            engine.resumeFailedProcess(PAYMENT, ORDER, State.DONE);

            assertEquals(ProcessStatus.COMPLETED, engine.processes().byId(id).single().orElseThrow().status());
        }
    }

    @Test
    void rejectsMissingAndAmbiguousAddressesForEveryOperation() {
        try (var engine = newProcessEngineWith(definition(PAYMENT))) {
            assertAddressRejected(engine, ProcessNotFoundException.class);
            var completed = engine.newProcess(PAYMENT).businessKey(ORDER).start();
            engine.sendMessageSync(completed, SIGNAL, "received");
            var waiting = engine.newProcess(PAYMENT).businessKey(ORDER).start();

            assertAddressRejected(engine, TooManyRowsException.class);

            assertEquals(ProcessStatus.ACTIVE, emulator(engine).runUntilStop(waiting).status());
        }
    }

    private static void assertAddressRejected(ProcessEngine engine, Class<? extends RuntimeException> errorType) {
        assertAll(
                () -> assertThrows(errorType, () -> engine.sendMessage(PAYMENT, ORDER, SIGNAL, "received")),
                () -> assertThrows(errorType, () -> engine.sendMessageSync(PAYMENT, ORDER, SIGNAL, "received")),
                () -> assertThrows(errorType, () -> engine.resumeFailedProcess(PAYMENT, ORDER)),
                () -> assertThrows(errorType, () -> engine.resumeFailedProcess(PAYMENT, ORDER, State.DONE)));
    }

    private static ProcessDefinition definition(ProcessType type) {
        return builder -> builder.type(type)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                if (message.payload().equals("fail")) {
                    throw new IllegalStateException("signal failed");
                }
            }, State.DONE))
            .terminal(State.DONE)
            .build();
    }

    private enum State implements ProcessState {
        WAIT,
        DONE
    }
}
