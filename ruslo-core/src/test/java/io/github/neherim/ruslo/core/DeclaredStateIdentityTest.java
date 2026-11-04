package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.test.TestProcessContext;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The engine returns the type and state objects declared in the definition, not canonical ids. */
class DeclaredStateIdentityTest extends BaseTest {
    private static final MessageType<String> APPROVE = MessageType.of("approve", String.class);
    private static final MessageType<String> OTHER = MessageType.of("other", String.class);

    private enum Type implements ProcessType {
        ORDER
    }

    private enum State implements ProcessState {
        CHECK,
        WAIT,
        DONE
    }

    private final List<ProcessType> seenTypes = new ArrayList<>();
    private final List<ProcessState> seenStates = new ArrayList<>();

    private final class OrderDefinition implements ProcessDefinition {
        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder.type(Type.ORDER)
                .initialState(State.CHECK)
                .step(State.CHECK, this::check, State.WAIT)
                .await(State.WAIT, await -> await
                    .message(APPROVE, (context, message) -> remember(context), State.DONE)
                    .message(OTHER, (context, message) -> remember(context), State.DONE))
                .terminal(State.DONE)
                .build();
        }

        private void check(ProcessContext context) {
            remember(context);
        }
    }

    private void remember(ProcessContext context) {
        seenTypes.add(context.processType());
        seenStates.add(context.state());
    }

    @Test
    void viewsReturnDeclaredTypeAndStates() {
        try (var engine = newProcessEngineWith(new OrderDefinition())) {
            var id = engine.newProcess(Type.ORDER).start();
            emulator(engine).runUntilStop(id);
            engine.sendMessage(id, APPROVE, "yes");
            var details = emulator(engine).runUntilStop(id);

            assertSame(Type.ORDER, details.type());
            assertSame(State.DONE, details.state());
            assertEquals(List.of(State.CHECK, State.WAIT, State.DONE),
                    details.stateHistory().stream().map(entry -> entry.state()).toList());
            details.stateHistory().forEach(entry -> assertSame(State.class, entry.state().getClass()));

            var summary = engine.processes().byId(id).single().orElseThrow();
            assertSame(Type.ORDER, summary.type());
            assertSame(State.DONE, summary.state());
        }
    }

    @Test
    void contextReturnsDeclaredTypeAndStateInStepAndAwait() {
        try (var engine = newProcessEngineWith(new OrderDefinition())) {
            var id = engine.newProcess(Type.ORDER).start();
            emulator(engine).runUntilStop(id);
            engine.sendMessage(id, APPROVE, "yes");
            emulator(engine).runUntilStop(id);

            assertEquals(List.of(Type.ORDER, Type.ORDER), seenTypes);
            assertSame(State.CHECK, seenStates.get(0));
            assertSame(State.WAIT, seenStates.get(1));
        }
    }

    @Test
    void messageNotExpectedReturnsDeclaredState() {
        try (var engine = newProcessEngineWith(new OrderDefinition())) {
            var id = engine.newProcess(Type.ORDER).start();

            var exception = assertThrows(MessageNotExpectedException.class,
                    () -> engine.sendMessage(id, APPROVE, "too early"));

            assertSame(State.CHECK, exception.getCurrentState());
        }
    }

    @Test
    void specInfoReturnsDeclaredTypeAndStates() {
        try (var engine = newProcessEngineWith(new OrderDefinition())) {
            var info = new ProcessEngineAdmin(engine).listProcessSpecifications().getFirst();

            assertSame(Type.ORDER, info.type());
            assertSame(State.CHECK, info.initialState());
            assertSame(State.WAIT, stateInfo(info, State.CHECK).transitions().getFirst().target());
            assertEquals(List.of(State.DONE, State.DONE), stateInfo(info, State.WAIT).transitions()
                .stream()
                .map(ProcessSpecInfo.TransitionInfo::target)
                .toList());
        }
    }

    @Test
    void countsReturnDeclaredType() {
        try (var engine = newProcessEngineWith(new OrderDefinition())) {
            engine.newProcess(Type.ORDER).start();

            var counts = new ProcessEngineAdmin(engine).countIncompleteProcessesByType(Instant.now());

            assertSame(Type.ORDER, counts.getFirst().type());
        }
    }

    @Test
    void stateUnknownToCurrentDefinitionIsReturnedByName() {
        var removed = ProcessState.of("REMOVED");
        ProcessDefinition original = builder -> builder.type(Type.ORDER)
            .initialState(removed)
            .step(removed, DeclaredStateIdentityTest::noop, State.DONE)
            .terminal(State.DONE)
            .build();
        UUID id;
        try (var engine = newProcessEngineWith(original)) {
            id = engine.newProcess(Type.ORDER).start();
        }
        try (var engine = newProcessEngineWith(new OrderDefinition())) {
            var details = engine.processes().byId(id).singleDetails().orElseThrow();

            assertSame(Type.ORDER, details.type());
            assertEquals(new ProcessStateId("REMOVED"), details.state());
            assertEquals(new ProcessStateId("REMOVED"), details.stateHistory().getFirst().state());
        }
    }

    @Test
    void unregisteredTypeIsReturnedByName() {
        UUID id;
        try (var engine = newProcessEngineWith(new OrderDefinition())) {
            id = engine.newProcess(Type.ORDER).start();
        }
        try (var engine = newProcessEngineWith()) {
            var details = engine.processes().byId(id).singleDetails().orElseThrow();

            assertEquals(new ProcessTypeId("ORDER"), details.type());
            assertEquals(new ProcessStateId("CHECK"), details.state());
        }
    }

    @Test
    void testContextReturnsGivenObjects() {
        var context = TestProcessContext.builder().processType(Type.ORDER).state(State.WAIT).build();

        assertSame(Type.ORDER, context.processType());
        assertSame(State.WAIT, context.state());
    }

    private static ProcessSpecInfo.StateInfo stateInfo(ProcessSpecInfo info, State state) {
        return info.states().stream().filter(s -> s.state() == state).findFirst().orElseThrow();
    }

    private static void noop(ProcessContext context) {
    }
}
