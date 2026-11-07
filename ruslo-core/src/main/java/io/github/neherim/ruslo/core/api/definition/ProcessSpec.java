package io.github.neherim.ruslo.core.api.definition;

import io.github.neherim.ruslo.core.api.exception.UnsupportedProcessStateException;

import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Immutable model of a process type: its state graph with handlers, transitions and error policy. A definition
 * builds it with {@link ProcessSpecBuilder}, which validates the graph, and returns it from
 * {@link ProcessDefinition#define}; the engine runs every process of the type by this model.
 * <p>
 * Methods taking a {@link ProcessState}, except {@link #hasState} and {@link #findStateSpec}, throw
 * {@link UnsupportedProcessStateException} if the process has no such state.
 */
public class ProcessSpec {
    private final ProcessType declaredType;
    private final String description;
    private final ProcessStateId initialState;
    private final Map<ProcessStateId, StateSpec> stateSpecs;
    private final ProcessErrorHandler processErrorHandler;
    private final ProcessStateId giveUpState;
    private final Set<String> timerNames;

    ProcessSpec(
            ProcessType declaredType,
            String description,
            ProcessStateId initialState,
            Map<ProcessStateId, StateSpec> stateSpecs,
            ProcessErrorHandler processErrorHandler,
            ProcessStateId giveUpState
    ) {
        this.declaredType = declaredType;
        this.description = description;
        this.initialState = initialState;
        this.stateSpecs = Map.copyOf(stateSpecs);
        this.processErrorHandler = processErrorHandler;
        this.giveUpState = giveUpState;
        this.timerNames = stateSpecs.values()
            .stream()
            .filter(spec -> spec instanceof AwaitState await && await.at() != null)
            .map(spec -> ((AwaitState) spec).at().timer().name())
            .collect(Collectors.toUnmodifiableSet());
    }

    public ProcessTypeId getType() {
        return declaredType.id();
    }

    /**
     * Returns the type object passed to {@link ProcessSpecBuilder#type(ProcessType)}.
     */
    public ProcessType declaredType() {
        return declaredType;
    }

    /**
     * Returns the declared state object, or {@link ProcessState#of(String)} if the process does not declare it.
     */
    public ProcessState declaredState(String name) {
        var state = stateSpecs.get(ProcessState.of(name));
        return state != null ? state.declaration() : ProcessState.of(name);
    }

    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    /**
     * Returns {@link ProcessState#description()} of the declared state.
     */
    public String getStateDescription(ProcessState state) {
        return getStateSpec(state).declaration().description();
    }

    public ProcessStateId getInitialState() {
        return initialState;
    }

    public Collection<StateSpec> getAllStateSpecs() {
        return stateSpecs.values();
    }

    public StateSpec getStateSpec(ProcessState state) {
        return findStateSpec(state)
            .orElseThrow(() -> new UnsupportedProcessStateException(getType().name(), state.name()));
    }

    /**
     * Returns the state's specification, or empty if the process does not declare the state.
     */
    public Optional<StateSpec> findStateSpec(ProcessState state) {
        return Optional.ofNullable(stateSpecs.get(state.id()));
    }

    /**
     * Returns the worker of a step; awaits always run on {@link WorkerId#DEFAULT}.
     *
     * @throws IllegalStateException for a terminal state
     */
    public WorkerId getWorkerId(ProcessState state) {
        return switch (getStateSpec(state)) {
            case StepState step -> step.worker();
            case AwaitState ignored -> WorkerId.DEFAULT;
            case TerminalState ignored ->
                throw new IllegalStateException("Terminal state has no worker: " + state.name());
        };
    }

    /**
     * Returns the state's event for this message type, matching both name and payload class; empty for non-await
     * states.
     */
    public Optional<MessageEvent<?>> getMessageEvent(MessageType<?> type, ProcessState state) {
        return getMessageEvent(type.name(), state).filter(e -> e.type().clazz().equals(type.clazz()));
    }

    /**
     * Returns the state's event for this message name, ignoring the payload class; empty for non-await states.
     */
    public Optional<MessageEvent<?>> getMessageEvent(String name, ProcessState state) {
        if (getStateSpec(state) instanceof AwaitState await) {
            return await.message(name);
        }
        return Optional.empty();
    }

    /**
     * Returns {@code true} if any await of the process declares this message type.
     */
    public boolean declaresMessage(MessageType<?> type) {
        return stateSpecs.values()
            .stream()
            .anyMatch(spec -> spec instanceof AwaitState await
                    && await.messages().stream().anyMatch(event -> event.type().equals(type)));
    }

    /**
     * Returns the message names the state accepts; empty for non-await states.
     */
    public Set<String> getApplicableMessageTypeNames(ProcessState state) {
        if (getStateSpec(state) instanceof AwaitState await) {
            return await.messageTypeNames();
        }
        return Set.of();
    }

    /**
     * @throws IllegalArgumentException if no await of the process has an {@code at} for the timer
     */
    public void checkTimerDeclared(TimerType timer) {
        if (!timerNames.contains(timer.name())) {
            throw new IllegalArgumentException("Timer " + timer.name() + " is not used by any at of process "
                    + getType().name());
        }
    }

    public ProcessErrorHandler getProcessErrorHandler() {
        return processErrorHandler;
    }

    /**
     * Returns the state entered on {@link ErrorResult#giveUp()}.
     */
    public Optional<ProcessStateId> getGiveUpState() {
        return Optional.ofNullable(giveUpState);
    }

    public boolean hasState(ProcessState state) {
        return findStateSpec(state).isPresent();
    }

    /**
     * Engine model of a state.
     */
    public sealed interface StateSpec permits StepState, AwaitState, TerminalState {
        /** Returns the state object passed to the builder. */
        ProcessState declaration();

        /** Returns the identity of the declared state. */
        default ProcessStateId state() {
            return declaration().id();
        }
    }

    /**
     * Step state.
     */
    public record StepState(
            ProcessState declaration,
            Function<ProcessContext, Enum<?>> step,
            WorkerId worker,
            Exit exit
    ) implements StateSpec {
    }

    /**
     * Await state and its events; {@code every}, {@code after} and {@code at} are nullable.
     */
    public record AwaitState(
            ProcessState declaration,
            List<MessageEvent<?>> messages,
            EveryEvent every,
            AfterEvent after,
            AtEvent at
    ) implements StateSpec {
        public AwaitState {
            messages = List.copyOf(messages);
        }

        /** Returns the event for this message name, ignoring the payload class. */
        public Optional<MessageEvent<?>> message(String name) {
            return messages.stream().filter(event -> event.type().name().equals(name)).findFirst();
        }

        /** Returns the names of the accepted messages. */
        public Set<String> messageTypeNames() {
            return messages.stream().map(event -> event.type().name()).collect(Collectors.toUnmodifiableSet());
        }
    }

    /**
     * Final state: the process completes on entering it.
     */
    public record TerminalState(ProcessState declaration) implements StateSpec {
    }

    /**
     * Message event, see
     * {@link ProcessSpecBuilder.AwaitBuilder#message(MessageType, ProcessMessageHandler, ProcessState)}.
     */
    public record MessageEvent<T>(
            MessageType<T> type,
            BiFunction<ProcessContext, IncomingMessage<T>, Enum<?>> handler,
            Exit exit
    ) {
    }

    /**
     * Periodic event, see {@link ProcessSpecBuilder.AwaitBuilder#every(Interval, ProcessTimerHandler, ProcessState)}.
     */
    public record EveryEvent(
            Interval interval,
            Function<ProcessContext, Enum<?>> handler,
            Exit exit
    ) {
    }

    /**
     * One-time event, see {@link ProcessSpecBuilder.AwaitBuilder#after(Duration, ProcessTimerHandler, ProcessState)}.
     */
    public record AfterEvent(Duration delay, Function<ProcessContext, Enum<?>> handler, Exit exit) {
    }

    /**
     * Event at the time set for the process {@code timer}, see {@link TimerType}.
     */
    public record AtEvent(
            TimerType timer,
            Function<ProcessContext, Enum<?>> handler,
            Exit exit
    ) {
    }

    /**
     * Where the process goes after a step or event. With {@link Routes} the step or handler returns an outcome that
     * is a key of the routes; with {@link Target} it returns {@code null}.
     */
    public sealed interface Exit permits Target, Routes {
    }

    /**
     * Fixed target.
     */
    public record Target(ProcessStateId state) implements Exit {
    }

    /**
     * Target chosen by outcome.
     */
    public record Routes(Map<Enum<?>, ProcessStateId> routes) implements Exit {
        public Routes {
            routes = Map.copyOf(routes);
        }
    }
}
