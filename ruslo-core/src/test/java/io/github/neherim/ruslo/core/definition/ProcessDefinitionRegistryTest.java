package io.github.neherim.ruslo.core.definition;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo.StateInfo;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo.StateKind;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo.TransitionInfo;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo.TransitionTrigger;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProcessDefinitionRegistryTest {
    private static final MessageType<String> APPROVE = MessageType.of("APPROVE", String.class);
    private static final MessageType<String> REVIEW = MessageType.of("REVIEW", String.class);

    @Test void exposesStateDescriptionsAndTransitions() {
        ProcessDefinition definition = builder -> builder.type("REGISTRY_TEST").initialState(State.CHECK)
            .step(State.CHECK, context -> Outcome.OK, routes -> routes
                .on(Outcome.OK, State.WAIT).on(Outcome.FAIL, State.DONE))
            .await(State.WAIT, await -> await
                .message(APPROVE, (context, message) -> { }, State.DONE)
                .message(REVIEW, (context, message) -> Outcome.OK, routes -> routes
                    .on(Outcome.OK, State.DONE).on(Outcome.FAIL, State.WAIT))
                .every(Duration.ofSeconds(30), context -> { }, State.WAIT)
                .timeout(Duration.ofMinutes(5), State.DONE))
            .terminal(State.DONE).build();
        var registry = new ProcessDefinitionRegistry(List.of(definition));

        var info = registry.getProcessDefinitions().getFirst();

        var check = state(info.states(), State.CHECK);
        assertEquals("Check", check.description());
        assertEquals(StateKind.STEP, check.kind());
        assertEquals(List.of(
            transition(TransitionTrigger.STEP, null, "OK", State.WAIT),
            transition(TransitionTrigger.STEP, null, "FAIL", State.DONE)), check.transitions());

        var wait = state(info.states(), State.WAIT);
        assertEquals("WAIT", wait.description());
        assertEquals(List.of(
            transition(TransitionTrigger.MESSAGE, "APPROVE", null, State.DONE),
            transition(TransitionTrigger.MESSAGE, "REVIEW", "OK", State.DONE),
            transition(TransitionTrigger.MESSAGE, "REVIEW", "FAIL", State.WAIT),
            transition(TransitionTrigger.EVERY, null, null, State.WAIT),
            transition(TransitionTrigger.TIMEOUT, null, null, State.DONE)), wait.transitions());

        assertEquals(List.of(), state(info.states(), State.DONE).transitions());
    }

    private static StateInfo state(java.util.Set<StateInfo> states, State state) {
        return states.stream().filter(info -> info.state().is(state)).findFirst().orElseThrow();
    }

    private static TransitionInfo transition(TransitionTrigger trigger, String messageType, String outcome,
                                             State target) {
        return new TransitionInfo(trigger, Optional.ofNullable(messageType), Optional.ofNullable(outcome),
            target.id());
    }

    private enum Outcome { OK, FAIL }

    private enum State implements ProcessState {
        CHECK("Check"), WAIT(null), DONE(null);

        private final String description;

        State(String description) {
            this.description = description;
        }

        @Override
        public String description() {
            return description == null ? name() : description;
        }
    }
}
