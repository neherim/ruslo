package io.github.neherim.ruslo.core.definition;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProcessSpecBuilderTest {
    private static final ProcessType TYPE = ProcessType.of("TEST");
    private static final ProcessStep STEP = context -> {};
    private static final BranchingStep<Outcome> BRANCHING = context -> Outcome.A;
    private static final MessageType<String> MESSAGE = MessageType.of("MESSAGE", String.class);
    private static final ProcessMessageHandler<String> ON_MESSAGE = (context, message) -> {};
    private static final ProcessTimerHandler ON_TIMER = context -> {};
    private static final TimerType DUE_DATE = TimerType.of("dueDate");

    @Test
    void buildsStepAwaitAndTerminal() {
        var spec = new ProcessSpecBuilder().type(TYPE)
            .defaultWorker(new WorkerId("DEFAULT_WORKER"))
            .initialState(State.START)
            .step(State.START, STEP, State.WAIT)
            .step(State.OTHER, new WorkerId("OWN_WORKER"), STEP, State.DONE)
            .await(State.WAIT, await -> await.message(MESSAGE, ON_MESSAGE, State.WAIT)
                .every(Duration.ofSeconds(1), ON_TIMER, State.WAIT)
                .after(Duration.ofSeconds(2), State.DONE))
            .terminal(State.DONE)
            .build();
        assertInstanceOf(StepState.class, spec.getStateSpec(State.START));
        assertInstanceOf(AwaitState.class, spec.getStateSpec(State.WAIT));
        assertInstanceOf(TerminalState.class, spec.getStateSpec(State.DONE));
        assertEquals(new WorkerId("DEFAULT_WORKER"), spec.getWorkerId(State.START));
        assertEquals(new WorkerId("OWN_WORKER"), spec.getWorkerId(State.OTHER));
        assertEquals(WorkerId.DEFAULT, spec.getWorkerId(State.WAIT));
        assertThrows(IllegalStateException.class, () -> spec.getWorkerId(State.DONE));
        assertEquals(MESSAGE, spec.getMessageEvent(MESSAGE, State.WAIT).orElseThrow().type());
        assertEquals(Set.of("MESSAGE"), spec.getApplicableMessageTypeNames(State.WAIT));
        var await = (AwaitState) spec.getStateSpec(State.WAIT);
        assertEquals(Interval.of(Duration.ofSeconds(1)), await.every().interval());
        assertEquals(Duration.ofSeconds(1), await.every().interval().firstAfter());
        assertEquals(Duration.ofSeconds(2), await.after().delay());
    }

    @Test
    void combinesAllTimersAndAllowsSelfTargetWithHandler() {
        var spec = new ProcessSpecBuilder().type(TYPE)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.every(Duration.ofMinutes(1), ON_TIMER, State.WAIT)
                .after(Duration.ofHours(1), ON_TIMER, State.WAIT)
                .at(DUE_DATE, context -> Outcome.A, routes -> routes
                    .on(Outcome.A, State.WAIT)
                    .on(Outcome.B, State.DONE)))
            .terminal(State.DONE)
            .build();
        var await = (AwaitState) spec.getStateSpec(State.WAIT);
        assertEquals(Duration.ofHours(1), await.after().delay());
        assertEquals(new Target(State.WAIT.id()), await.after().exit());
        assertEquals(DUE_DATE, await.at().timer());
        assertInstanceOf(ProcessSpec.Routes.class, await.at().exit());
    }

    @Test
    void setsEveryFirstCheck() {
        var spec = new ProcessSpecBuilder().type(TYPE)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await.every(Interval.of(Duration.ofMinutes(5)).firstAfter(Duration.ZERO),
                    ON_TIMER, State.DONE))
            .await(State.OTHER, await -> await.every(
                    Interval.of(Duration.ofMinutes(5)).firstAfter(Duration.ofSeconds(10)),
                    context -> Outcome.A, routes -> routes.on(Outcome.A, State.DONE)
                        .on(Outcome.B, State.WAIT)))
            .terminal(State.DONE)
            .build();
        assertEquals(Duration.ZERO, ((AwaitState) spec.getStateSpec(State.WAIT)).every().interval().firstAfter());
        assertEquals(Duration.ofSeconds(10),
                ((AwaitState) spec.getStateSpec(State.OTHER)).every().interval().firstAfter());
    }

    @Test
    void acceptsInitialAwaitAndAllHandlerForms() {
        var spec = new ProcessSpecBuilder().type(TYPE)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await
                .message(MESSAGE, ON_MESSAGE, State.START)
                .message(MessageType.of("BRANCH", String.class), (context, message) -> message.payload().isEmpty()
                        ? Outcome.B
                        : Outcome.A,
                        routes -> routes
                            .on(Outcome.A, State.START)
                            .on(Outcome.B, State.WAIT))
                .every(Duration.ofMinutes(1), ON_TIMER, State.WAIT))
            .step(State.START, BRANCHING, routes -> routes
                .on(Outcome.A, State.DONE)
                .on(Outcome.B, State.WAIT))
            .terminal(State.DONE)
            .build();
        assertEquals(State.WAIT.id(), spec.getInitialState());
    }

    @Test
    void buildsTypicalSmallLinearProcess() {
        var spec = new ProcessSpecBuilder().type("USER_REGISTRATION")
            .description("User registration with email confirmation")
            .onError(ProcessErrorHandler.fixed(3, Duration.ofSeconds(1)))
            .initialState(State.START)
            .step(State.START, STEP, State.OTHER)
            .step(State.OTHER, STEP, State.WAIT)
            .await(State.WAIT, await -> await
                .message(MESSAGE, ON_MESSAGE, State.AFTER)
                .after(Duration.ofDays(3), State.DONE))
            .step(State.AFTER, STEP, State.DONE)
            .terminal(State.DONE)
            .build();
        assertEquals(5, spec.getAllStateSpecs().size());
    }

    @Test
    void takesStateDescriptionFromDeclaration() {
        var spec = new ProcessSpecBuilder().type(TYPE)
            .initialState(DescribedState.START)
            .step(DescribedState.START, STEP, ProcessState.of("DONE"))
            .terminal(ProcessState.of("DONE"))
            .build();
        assertEquals("Start", spec.getStateDescription(ProcessState.of("START")));
        assertEquals("DONE", spec.getStateDescription(DescribedState.DONE));
    }

    @Test
    void rejectsBlankStateDescription() {
        assertRejected("Description of state BLANK", () -> new ProcessSpecBuilder().type(TYPE)
            .initialState(DescribedState.BLANK)
            .step(DescribedState.BLANK, STEP, DescribedState.DONE));
    }

    @Test
    void rejectsInvalidDefinitions() {
        assertRejected("already defined", () -> new ProcessSpecBuilder().type(TYPE)
            .initialState(State.START)
            .step(State.START, STEP, State.DONE)
            .step(State.START, STEP, State.DONE));
        assertRejected("must be a declared step or await", () -> start().terminal(State.START).build());
        assertRejected("must be a declared step or await", () -> new ProcessSpecBuilder().type(TYPE)
            .initialState(State.DONE)
            .terminal(State.DONE)
            .build());
        assertRejected("is not declared", () -> start().step(State.START, STEP, State.OTHER).build());
        assertRejected("is not declared", () -> start().step(State.START, BRANCHING, routes -> routes
            .on(Outcome.A, State.DONE)
            .on(Outcome.B, State.OTHER)).build());
        assertRejected("is not declared", () -> startWithStep().await(State.WAIT,
                await -> await.after(Duration.ofSeconds(1), State.OTHER))
            .build());
        assertRejected("at least one on", () -> start().step(State.START, BRANCHING, routes -> {}));
        assertRejected("Duplicate outcome", () -> start().step(State.START, BRANCHING, routes -> routes
            .on(Outcome.A, State.DONE)
            .on(Outcome.A, State.DONE)));
        assertRejected("do not cover all constants", () -> start().step(State.START, BRANCHING,
                routes -> routes.on(Outcome.A, State.DONE))
            .terminal(State.DONE)
            .build());
        assertRejected("is a process state",
                () -> start().step(State.START, (BranchingStep<StateOutcome>) context -> StateOutcome.X,
                        routes -> routes.on(StateOutcome.X, State.DONE)));
        assertRejected("Duplicate message", () -> startWithStep().await(State.WAIT,
                await -> await.message(MESSAGE, ON_MESSAGE, State.DONE).message(MESSAGE, ON_MESSAGE, State.DONE)));
        assertRejected("Duplicate every", () -> startWithStep().await(State.WAIT, await -> await
            .every(Duration.ofSeconds(1), ON_TIMER, State.DONE)
            .every(Duration.ofSeconds(1), ON_TIMER, State.DONE)));
        assertRejected("Duplicate after", () -> startWithStep().await(State.WAIT, await -> await
            .after(Duration.ofSeconds(1), State.DONE)
            .after(Duration.ofSeconds(1), State.DONE)));
        assertRejected("Duplicate at", () -> startWithStep().await(State.WAIT, await -> await
            .at(DUE_DATE, State.DONE)
            .at(DUE_DATE, ON_TIMER, State.DONE)));
        assertRejected("no message, every, after or at", () -> startWithStep().await(State.WAIT, await -> {})
            .terminal(State.DONE)
            .build());
        assertRejected("no event leading to another state", () -> startWithStep().await(State.WAIT,
                await -> await.message(MESSAGE, ON_MESSAGE, State.WAIT))
            .terminal(State.DONE)
            .build());
        assertRejected("no event leading to another state", () -> startWithStep().await(State.WAIT,
                await -> await.every(Duration.ofSeconds(1), context -> Outcome.A, routes -> routes
                    .on(Outcome.A, State.WAIT)
                    .on(Outcome.B, State.WAIT)))
            .terminal(State.DONE)
            .build());
        assertRejected("no event leading to another state", () -> startWithStep().await(State.WAIT,
                await -> await.after(Duration.ofSeconds(1), ON_TIMER, State.WAIT)
                    .at(DUE_DATE, context -> Outcome.A, routes -> routes
                        .on(Outcome.A, State.WAIT)
                        .on(Outcome.B, State.WAIT)))
            .terminal(State.DONE)
            .build());
        assertRejected("cannot target its own await state", () -> startWithStep().await(State.WAIT,
                await -> await.after(Duration.ofSeconds(1), State.WAIT)));
        assertRejected("cannot target its own await state", () -> startWithStep().await(State.WAIT,
                await -> await.at(DUE_DATE, State.WAIT)));
        assertRejected("do not cover all constants", () -> startWithStep().await(State.WAIT,
                await -> await.at(DUE_DATE, context -> Outcome.A, routes -> routes.on(Outcome.A, State.DONE)))
            .terminal(State.DONE)
            .build());
        assertRejected("must be positive", () -> startWithStep().await(State.WAIT,
                await -> await.after(Duration.ZERO, State.DONE)));
        assertRejected("must be positive", () -> startWithStep().await(State.WAIT,
                await -> await.every(Duration.ofSeconds(-1), ON_TIMER, State.DONE)));
        assertRejected("cannot be negative", () -> startWithStep().await(State.WAIT,
                await -> await.every(Interval.of(Duration.ofSeconds(1)).firstAfter(Duration.ofSeconds(-1)), ON_TIMER,
                        State.DONE)));
        assertRejected("conflicting payload classes", () -> startWithStep().await(State.WAIT, await -> await
            .message(MESSAGE, ON_MESSAGE, State.WAIT)
            .after(Duration.ofSeconds(1), State.DONE))
            .await(State.OTHER, await -> await.message(MessageType.of("MESSAGE", Integer.class),
                    (context, message) -> {}, State.OTHER)
                .after(Duration.ofSeconds(1), State.DONE))
            .terminal(State.DONE)
            .build());
    }

    @Test
    void checksGiveUpStateIsDeclared() {
        assertRejected("onGiveUp state", () -> new ProcessSpecBuilder().type(TYPE)
            .onGiveUp(State.OTHER)
            .initialState(State.START)
            .step(State.START, STEP, State.DONE)
            .terminal(State.DONE)
            .build());
        var spec = new ProcessSpecBuilder().type(TYPE)
            .onGiveUp(State.DONE)
            .initialState(State.START)
            .step(State.START, STEP, State.DONE)
            .terminal(State.DONE)
            .build();
        assertEquals(State.DONE.id(), spec.getGiveUpState().orElseThrow());
    }

    @Test
    void validatesMessageTypeAndErrorResultArguments() {
        assertThrows(IllegalArgumentException.class, () -> MessageType.of(" ", String.class));
        assertThrows(NullPointerException.class, () -> MessageType.of("x", (Class<String>) null));
        assertThrows(IllegalArgumentException.class, () -> ErrorResult.retryAfter(Duration.ofSeconds(-1)));
    }

    @Test
    void invokesHandlersPassedToTheDsl() {
        var calls = new ArrayList<String>();
        ProcessStep linear = context -> calls.add("linear");
        BranchingStep<Outcome> branching = context -> {
            calls.add("branching");
            return Outcome.B;
        };
        ProcessMessageHandler<String> messageHandler = (context, message) -> calls.add("message");
        ProcessTimerHandler timerHandler = context -> calls.add("timer");

        var spec = new ProcessSpecBuilder().type(TYPE)
            .initialState(State.START)
            .step(State.START, linear, State.WAIT)
            .step(State.OTHER, branching, routes -> routes.on(Outcome.A, State.DONE).on(Outcome.B, State.WAIT))
            .await(State.WAIT, await -> await.message(MESSAGE, messageHandler, State.DONE)
                .every(Duration.ofSeconds(1), timerHandler, State.WAIT))
            .terminal(State.DONE)
            .build();

        var step = assertInstanceOf(StepState.class, spec.getStateSpec(State.START));
        var await = assertInstanceOf(AwaitState.class, spec.getStateSpec(State.WAIT));
        assertNull(step.step().apply(null));
        assertEquals(Outcome.B, ((StepState) spec.getStateSpec(State.OTHER)).step().apply(null));
        assertNull(callMessage(await.messages().getFirst(), MESSAGE, "x"));
        assertNull(await.every().handler().apply(null));
        assertEquals(List.of("linear", "branching", "message", "timer"), calls);
        assertEquals(new Target(State.WAIT.id()), step.exit());
        assertEquals(new Target(State.WAIT.id()), await.every().exit());
    }

    @Test
    void routesDifferentPayloadTypesToMethodsOfTheSameHandlerObject() {
        var handlers = new MultiMessageHandlers();
        var paymentType = MessageType.of("PAYMENT", Payment.class);
        var cancellationType = MessageType.of("CANCELLATION", String.class);
        var spec = new ProcessSpecBuilder().type(TYPE)
            .initialState(State.WAIT)
            .await(State.WAIT, await -> await
                .message(paymentType, handlers::payment, State.DONE)
                .message(cancellationType, handlers::cancellation, State.DONE))
            .terminal(State.DONE)
            .build();

        var payment = spec.getMessageEvent(paymentType, State.WAIT).orElseThrow();
        var cancellation = spec.getMessageEvent(cancellationType, State.WAIT).orElseThrow();
        callMessage(payment, paymentType, new Payment("tx-1"));
        callMessage(cancellation, cancellationType, "duplicate");

        assertEquals(List.of("payment:tx-1", "cancellation:duplicate"), handlers.calls);
    }

    private static <T> Enum<?> callMessage(MessageEvent<?> event, MessageType<T> type, T payload) {
        @SuppressWarnings("unchecked")
        var typed = (MessageEvent<T>) event;
        return typed.handler()
            .apply(null, new IncomingMessage<>(UUID.randomUUID(), type, payload,
                    java.time.Instant.now()));
    }

    private static ProcessSpecBuilder.StatesBuilder start() {
        return new ProcessSpecBuilder().type(TYPE).initialState(State.START);
    }

    private static ProcessSpecBuilder.StatesBuilder startWithStep() {
        return start().step(State.START, STEP, State.WAIT);
    }

    private static void assertRejected(String messagePart, Runnable definition) {
        var ex = assertThrows(IllegalArgumentException.class, definition::run);
        assertTrue(ex.getMessage().contains(messagePart), ex.getMessage());
    }

    private static final class MultiMessageHandlers {
        private final List<String> calls = new ArrayList<>();

        private void payment(ProcessContext context, IncomingMessage<Payment> message) {
            calls.add("payment:" + message.payload().transactionId());
        }

        private void cancellation(ProcessContext context, IncomingMessage<String> message) {
            calls.add("cancellation:" + message.payload());
        }
    }

    private record Payment(String transactionId) {
    }

    private enum Outcome {
        A,
        B
    }

    private enum StateOutcome implements ProcessState {
        X
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        OTHER,
        AFTER,
        DONE
    }

    private enum DescribedState implements ProcessState {
        START("Start"),
        BLANK(" "),
        DONE("End");

        private final String description;

        DescribedState(String description) {
            this.description = description;
        }

        @Override
        public String description() {
            return description;
        }
    }
}
