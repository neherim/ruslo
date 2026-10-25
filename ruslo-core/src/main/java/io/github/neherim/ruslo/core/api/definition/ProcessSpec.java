package io.github.neherim.ruslo.core.api.definition;

import io.github.neherim.ruslo.core.api.exception.UnsupportedProcessStateException;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Validated states and handlers of a process type. Created only by {@link ProcessSpecBuilder}.
 */
public class ProcessSpec {
    private final ProcessTypeId type;
    private final String description;
    private final ProcessStateId initialState;
    private final Map<String, StateSpec> stateSpecs;
    private final Map<String, String> stateDescriptions;
    private final ProcessErrorHandler processErrorHandler;
    private final ProcessStateId fallbackState;
    private final Set<String> timerNames;

    ProcessSpec(
            ProcessTypeId type,
            String description,
            ProcessStateId initialState,
            Map<String, StateSpec> stateSpecs,
            Map<String, String> stateDescriptions,
            ProcessErrorHandler processErrorHandler,
            ProcessStateId fallbackState
    ) {
        this.type = type;
        this.description = description;
        this.initialState = initialState;
        this.stateSpecs = Map.copyOf(stateSpecs);
        this.stateDescriptions = Map.copyOf(stateDescriptions);
        this.processErrorHandler = processErrorHandler;
        this.fallbackState = fallbackState;
        this.timerNames = stateSpecs.values()
            .stream()
            .filter(spec -> spec instanceof AwaitState await && await.at() != null)
            .map(spec -> ((AwaitState) spec).at().timer().name())
            .collect(Collectors.toUnmodifiableSet());
    }

    public ProcessTypeId getType() {
        return type;
    }

    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    /** Returns the state description given in its declaration. */
    public String getStateDescription(ProcessState state) {
        getStateSpec(state);
        return stateDescriptions.get(state.name());
    }

    public ProcessStateId getInitialState() {
        return initialState;
    }

    public Collection<StateSpec> getAllStateSpecs() {
        return stateSpecs.values();
    }

    /** @throws UnsupportedProcessStateException if the process has no such state */
    public StateSpec getStateSpec(ProcessState state) {
        var spec = stateSpecs.get(state.name());
        if (spec == null) throw new UnsupportedProcessStateException(type.name(), state.name());
        return spec;
    }

    /** Returns the worker of a step; awaits always run on {@link WorkerId#DEFAULT}. */
    public WorkerId getWorkerId(ProcessState state) {
        return switch (getStateSpec(state)) {
            case StepState step -> step.worker();
            case AwaitState ignored -> WorkerId.DEFAULT;
            case TerminalState ignored ->
                throw new IllegalStateException("Terminal state has no worker: " + state.name());
        };
    }

    /** Returns the state's event for this message type, matching both name and payload class. */
    public Optional<MessageEvent<?>> getMessageEvent(MessageType<?> type, ProcessState state) {
        return getMessageEvent(type.name(), state).filter(e -> e.type().clazz().equals(type.clazz()));
    }

    /** Returns the state's event for a stored message name. */
    public Optional<MessageEvent<?>> getMessageEvent(String name, ProcessState state) {
        if (getStateSpec(state) instanceof AwaitState await) {
            return await.messages().stream().filter(e -> e.type().name().equals(name)).findFirst();
        }
        return Optional.empty();
    }

    /** Returns {@code true} if any await of the process declares this message type. */
    public boolean declaresMessage(MessageType<?> type) {
        return stateSpecs.values()
            .stream()
            .anyMatch(spec -> spec instanceof AwaitState await
                    && await.messages().stream().anyMatch(event -> event.type().equals(type)));
    }

    /** Returns the message names the state accepts; empty for non-await states. */
    public Set<String> getApplicableMessageTypeNames(ProcessState state) {
        if (getStateSpec(state) instanceof AwaitState await) {
            return await.messages().stream().map(e -> e.type().name()).collect(Collectors.toUnmodifiableSet());
        }
        return Set.of();
    }

    /**
     * Checks that an {@code at} of some await of the process waits for the timer.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     */
    public void checkTimerDeclared(TimerType timer) {
        if (!timerNames.contains(timer.name())) {
            throw new IllegalArgumentException("Timer " + timer.name() + " is not used by any at of process "
                    + type.name());
        }
    }

    public ProcessErrorHandler getProcessErrorHandler() {
        return processErrorHandler;
    }

    /** Returns the state entered on {@link ErrorResult#fallback()}. */
    public Optional<ProcessStateId> getFallbackState() {
        return Optional.ofNullable(fallbackState);
    }

    public boolean hasState(ProcessState state) {
        return stateSpecs.containsKey(state.name());
    }

    /** Engine model of a state. */
    public sealed interface StateSpec permits StepState, AwaitState, TerminalState {
        ProcessStateId state();
    }

    /** Step state. {@code step} returns the outcome of a branching step, {@code null} otherwise. */
    public record StepState(ProcessStateId state, Function<ProcessContext, Enum<?>> step, WorkerId worker, Exit exit)
            implements StateSpec {
    }

    /** Await state and its events; {@code every}, {@code after} and {@code at} are nullable. */
    public record AwaitState(
            ProcessStateId state,
            List<MessageEvent<?>> messages,
            EveryEvent every,
            AfterEvent after,
            AtEvent at
    ) implements StateSpec {
        public AwaitState {
            messages = List.copyOf(messages);
        }
    }

    public record TerminalState(ProcessStateId state) implements StateSpec {
    }

    /**
     * Message event. {@code handler} returns the outcome of a branching handler, {@code null} otherwise.
     * A target equal to the await itself keeps waiting.
     */
    public record MessageEvent<T>(
            MessageType<T> type,
            BiFunction<ProcessContext, IncomingMessage<T>, Enum<?>> handler,
            Exit exit
    ) {
    }

    /**
     * Periodic event: fires at entering the await plus {@code initialDelay} plus {@code k * interval}. The interval
     * counts from the planned points, not from handling; missed points are skipped.
     * {@code handler} returns the outcome of a branching handler, {@code null} otherwise.
     * A target equal to the await itself keeps waiting.
     */
    public record EveryEvent(
            Duration interval,
            Duration initialDelay,
            Function<ProcessContext, Enum<?>> handler,
            Exit exit
    ) {
    }

    /**
     * One-time event {@code delay} after entering the await; after firing with the await itself as the target it is
     * off until the await is entered again. {@code handler} returns the outcome of a branching handler, {@code null}
     * otherwise.
     */
    public record AfterEvent(Duration delay, Function<ProcessContext, Enum<?>> handler, Exit exit) {
    }

    /**
     * Event at the moment of a process {@code timer}: fires while the process is in this await and the timer is set
     * and due; a past moment fires at once. The fired timer is cancelled before the handler runs, so the handler sees
     * it unset and may set it again to make a schedule. See {@link ProcessContext#setTimer(TimerType, Instant)}.
     * {@code handler} returns the outcome of a branching handler, {@code null} otherwise.
     */
    public record AtEvent(
            TimerType timer,
            Function<ProcessContext, Enum<?>> handler,
            Exit exit
    ) {
    }

    /** Where the process goes after a step or event. */
    public sealed interface Exit permits Target, Routes {
    }

    /** Fixed target. */
    public record Target(ProcessStateId state) implements Exit {
    }

    /** Target chosen by outcome. */
    public record Routes(Map<Enum<?>, ProcessStateId> routes) implements Exit {
        public Routes {
            routes = Map.copyOf(routes);
        }
    }
}
