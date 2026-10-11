package io.github.neherim.ruslo.core.api.definition;

import io.github.neherim.ruslo.core.api.definition.ProcessSpec.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Builds a {@link ProcessSpec}: process settings first, then states. */
public class ProcessSpecBuilder {
    public ProcessSpecBuilder() {
    }

    /** Sets the process type. */
    public TypedBuilder type(ProcessType processType) {
        return new TypedBuilder(processType);
    }

    /** Sets the process type by name. */
    public TypedBuilder type(String processTypeName) {
        return type(ProcessType.of(processTypeName));
    }

    /** Process settings. */
    public static class TypedBuilder {
        private final Draft draft;

        private TypedBuilder(ProcessType type) {
            draft = new Draft(ProcessType.of(requireNonNull(type, "Process type cannot be null").name()));
        }

        public TypedBuilder description(String description) {
            draft.description = description;
            return this;
        }

        /** Sets the worker for steps that do not set their own. */
        public TypedBuilder defaultWorker(WorkerId workerId) {
            draft.defaultWorker = requireNonNull(workerId, "Worker id cannot be null");
            return this;
        }

        /** Sets the error handler; {@link ErrorResult#fallback()} then stops the process in {@code FAILED}. */
        public TypedBuilder onError(ProcessErrorHandler handler) {
            draft.errorHandler = requireNonNull(handler, "Process error handler cannot be null");
            return this;
        }

        /** Sets the error handler and the state entered on {@link ErrorResult#fallback()}. */
        public TypedBuilder onError(ProcessErrorHandler handler, ProcessState fallbackState) {
            onError(handler);
            draft.fallbackState = Draft.id(fallbackState);
            return this;
        }

        /** Sets the initial state (a step or an await) and starts declaring states. */
        public StatesBuilder initialState(ProcessState state) {
            draft.initial = Draft.id(state);
            return new StatesBuilder(draft);
        }
    }

    /** State declarations. */
    public static class StatesBuilder {
        private final Draft draft;

        private StatesBuilder(Draft draft) {
            this.draft = draft;
        }

        /** Declares a step that moves to {@code next} on success. */
        public StatesBuilder step(ProcessState state, ProcessStep step, ProcessState next) {
            return step(state, step, next, options -> { });
        }

        /** Declares a step that moves to {@code next} on success, with options. */
        public StatesBuilder step(ProcessState state, ProcessStep step, ProcessState next,
                                  Consumer<StepOptions> options) {
            requireNonNull(step, "Process step cannot be null");
            draft.addStep(state, context -> {
                step.execute(context);
                return null;
            }, new Target(Draft.id(next)), options(options));
            return this;
        }

        /** Declares a step whose outcome selects the target in {@code routes}. */
        public <O extends Enum<O>> StatesBuilder step(ProcessState state, BranchingStep<O> step,
                                                      Consumer<Routes<O>> routes) {
            return step(state, step, routes, options -> { });
        }

        /** Declares a step whose outcome selects the target in {@code routes}, with options. */
        public <O extends Enum<O>> StatesBuilder step(ProcessState state, BranchingStep<O> step,
                                                      Consumer<Routes<O>> routes, Consumer<StepOptions> options) {
            requireNonNull(step, "Process step cannot be null");
            draft.addStep(state, step::execute, draft.routes(routes, "step " + Draft.id(state).name()),
                options(options));
            return this;
        }

        /** Declares an await and its events. */
        public StatesBuilder await(ProcessState state, Consumer<AwaitBuilder> definition) {
            var id = Draft.id(state);
            var events = new AwaitBuilder(id, draft);
            requireNonNull(definition, "Await definition cannot be null").accept(events);
            draft.add(state, new AwaitState(id, events.messages, events.every, events.timeout));
            return this;
        }

        /** Declares a terminal state. */
        public StatesBuilder terminal(ProcessState state) {
            draft.add(state, new TerminalState(Draft.id(state)));
            return this;
        }

        /**
         * Validates the declarations and builds the spec.
         *
         * @throws IllegalArgumentException if the definition is inconsistent
         */
        public ProcessSpec build() {
            return draft.build();
        }

        private StepOptions options(Consumer<StepOptions> definition) {
            var result = new StepOptions(draft.defaultWorker);
            requireNonNull(definition, "Step options cannot be null").accept(result);
            return result;
        }
    }

    /** Step options. */
    public static class StepOptions {
        private WorkerId worker;

        private StepOptions(WorkerId worker) {
            this.worker = worker;
        }

        /** Runs the step on this worker instead of the default one. */
        public StepOptions worker(WorkerId worker) {
            this.worker = requireNonNull(worker, "Worker id cannot be null");
            return this;
        }
    }

    /** {@code every} options. */
    public static class EveryOptions {
        private Duration initialDelay;

        private EveryOptions(Duration interval) {
            this.initialDelay = interval;
        }

        /**
         * Sets the delay of the first check after entering the await; defaults to the interval.
         * {@link Duration#ZERO} checks immediately.
         */
        public EveryOptions initialDelay(Duration initialDelay) {
            this.initialDelay = requireNonNull(initialDelay, "Initial delay cannot be null");
            return this;
        }
    }

    /**
     * Outcome-to-target routes of a branching step or event.
     *
     * @param <O> outcome enum
     */
    public static class Routes<O extends Enum<O>> {
        private final String where;
        private final Map<Enum<?>, ProcessStateId> routes = new LinkedHashMap<>();

        private Routes(String where) {
            this.where = where;
        }

        /** Routes {@code outcome} to {@code target}. */
        public Routes<O> on(O outcome, ProcessState target) {
            requireNonNull(outcome, "Outcome cannot be null");
            requireNonNull(target, "Target state cannot be null");
            if (outcome instanceof ProcessState) {
                throw new IllegalArgumentException("Outcome " + outcome + " in " + where
                    + " is a process state: the target state is the second argument of on(...)");
            }
            if (routes.putIfAbsent(outcome, target.id()) != null) {
                throw new IllegalArgumentException("Duplicate outcome " + outcome + " in " + where);
            }
            return this;
        }
    }

    /** Events of one await. */
    public static class AwaitBuilder {
        private final ProcessStateId state;
        private final Draft draft;
        private final List<MessageEvent<?>> messages = new ArrayList<>();
        private EveryEvent every;
        private TimeoutEvent timeout;

        private AwaitBuilder(ProcessStateId state, Draft draft) {
            this.state = state;
            this.draft = draft;
        }

        /**
         * Handles the message and moves to {@code next}. If {@code next} is this await, the process keeps waiting
         * without re-entering it.
         */
        public <T> AwaitBuilder message(MessageType<T> type, ProcessMessageHandler<T> handler, ProcessState next) {
            requireNonNull(handler, "Message handler cannot be null");
            return addMessage(type, (context, message) -> {
                handler.handle(context, message);
                return null;
            }, new Target(Draft.id(next)));
        }

        /**
         * Handles the message; its outcome selects the target in {@code routes}. A target equal to this await
         * keeps waiting without re-entering it.
         */
        public <T, O extends Enum<O>> AwaitBuilder message(MessageType<T> type,
                                                           BranchingMessageHandler<T, O> handler,
                                                           Consumer<Routes<O>> routes) {
            requireNonNull(handler, "Message handler cannot be null");
            return addMessage(type, handler::handle, draft.routes(routes, where("message " + type.name())));
        }

        private <T> AwaitBuilder addMessage(MessageType<T> type,
                                            BiFunction<ProcessContext, IncomingMessage<T>, Enum<?>> handler,
                                            Exit exit) {
            requireNonNull(type, "Message type cannot be null");
            if (messages.stream().anyMatch(event -> event.type().name().equals(type.name()))) {
                throw new IllegalArgumentException("Duplicate message " + type.name() + " in " + where());
            }
            messages.add(new MessageEvent<>(type, handler, exit));
            return this;
        }

        /**
         * Calls {@code handler} every {@code interval} and moves to {@code next}. If {@code next} is this await,
         * the process keeps waiting and the next check is scheduled after {@code interval}.
         */
        public AwaitBuilder every(Duration interval, ProcessTimerHandler handler, ProcessState next) {
            return every(interval, handler, next, options -> { });
        }

        /** Same as {@link #every(Duration, ProcessTimerHandler, ProcessState)}, with options. */
        public AwaitBuilder every(Duration interval, ProcessTimerHandler handler, ProcessState next,
                                  Consumer<EveryOptions> options) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addEvery(interval, context -> {
                handler.handle(context);
                return null;
            }, new Target(Draft.id(next)), options);
        }

        /**
         * Calls {@code handler} every {@code interval}; its outcome selects the target in {@code routes}.
         * A target equal to this await keeps waiting until the next check.
         */
        public <O extends Enum<O>> AwaitBuilder every(Duration interval, BranchingTimerHandler<O> handler,
                                                      Consumer<Routes<O>> routes) {
            return every(interval, handler, routes, options -> { });
        }

        /** Same as {@link #every(Duration, BranchingTimerHandler, Consumer)}, with options. */
        public <O extends Enum<O>> AwaitBuilder every(Duration interval, BranchingTimerHandler<O> handler,
                                                      Consumer<Routes<O>> routes, Consumer<EveryOptions> options) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addEvery(interval, handler::handle, draft.routes(routes, where("every")), options);
        }

        private AwaitBuilder addEvery(Duration interval, Function<ProcessContext, Enum<?>> handler, Exit exit,
                                      Consumer<EveryOptions> definition) {
            requirePositive(interval, "every");
            if (every != null) throw new IllegalArgumentException("Duplicate every in " + where());
            var options = new EveryOptions(interval);
            requireNonNull(definition, "Every options cannot be null").accept(options);
            if (options.initialDelay.isNegative()) {
                throw new IllegalArgumentException("Initial delay of every in " + where() + " cannot be negative");
            }
            every = new EveryEvent(interval, options.initialDelay, handler, exit);
            return this;
        }

        /**
         * Moves to {@code next} {@code delay} after entering the await. {@code next} must not be this await.
         */
        public AwaitBuilder timeout(Duration delay, ProcessState next) {
            return addTimeout(delay, null, next);
        }

        /**
         * Calls {@code handler} {@code delay} after entering the await, then moves to {@code next}.
         * {@code next} must not be this await.
         */
        public AwaitBuilder timeout(Duration delay, ProcessTimerHandler handler, ProcessState next) {
            return addTimeout(delay, requireNonNull(handler, "Timeout handler cannot be null"), next);
        }

        private AwaitBuilder addTimeout(Duration delay, ProcessTimerHandler handler, ProcessState next) {
            requirePositive(delay, "timeout");
            if (timeout != null) throw new IllegalArgumentException("Duplicate timeout in " + where());
            if (state.is(Draft.id(next))) {
                throw new IllegalArgumentException("Timeout in " + where()
                    + " cannot target its own await state; use a step to restart waiting");
            }
            timeout = new TimeoutEvent(delay, handler, Draft.id(next));
            return this;
        }

        private void requirePositive(Duration duration, String event) {
            requireNonNull(duration, "Duration cannot be null");
            if (duration.isZero() || duration.isNegative()) {
                throw new IllegalArgumentException("Duration of " + event + " in " + where() + " must be positive");
            }
        }

        private String where() {
            return "await state " + state.name() + " of process " + draft.type.name();
        }

        private String where(String event) {
            return event + " in " + where();
        }
    }

    private static final class Draft {
        private final ProcessTypeId type;
        private final Map<String, StateSpec> states = new LinkedHashMap<>();
        private final Map<String, String> stateDescriptions = new HashMap<>();
        private String description;
        private WorkerId defaultWorker = WorkerId.DEFAULT;
        private ProcessErrorHandler errorHandler = ProcessErrorHandler.noRetry();
        private ProcessStateId fallbackState;
        private ProcessStateId initial;

        private Draft(ProcessTypeId type) {
            this.type = type;
        }

        private static ProcessStateId id(ProcessState state) {
            return requireNonNull(state, "Process state cannot be null").id();
        }

        private void add(ProcessState state, StateSpec spec) {
            var name = spec.state().name();
            if (states.putIfAbsent(name, spec) != null) {
                throw new IllegalArgumentException("State " + name + " is already defined in process " + type.name());
            }
            var stateDescription = state.description();
            if (stateDescription == null || stateDescription.isBlank()) {
                throw new IllegalArgumentException("Description of state " + name + " of process " + type.name()
                    + " cannot be blank");
            }
            stateDescriptions.put(name, stateDescription);
        }

        private void addStep(ProcessState state, Function<ProcessContext, Enum<?>> step, Exit exit,
                             StepOptions options) {
            add(state, new StepState(id(state), step, options.worker, exit));
        }

        private <O extends Enum<O>> ProcessSpec.Routes routes(Consumer<Routes<O>> definition, String where) {
            var result = new Routes<O>(where + " of process " + type.name());
            requireNonNull(definition, "Routes cannot be null").accept(result);
            if (result.routes.isEmpty()) {
                throw new IllegalArgumentException("Routes of " + result.where + " must contain at least one on(...)");
            }
            return new ProcessSpec.Routes(result.routes);
        }

        private ProcessSpec build() {
            if (!(states.get(initial.name()) instanceof StepState) && !(states.get(initial.name()) instanceof AwaitState)) {
                throw new IllegalArgumentException("Initial state " + initial.name() + " of process " + type.name()
                    + " must be a declared step or await");
            }
            var payloads = new HashMap<String, Class<?>>();
            for (var spec : states.values()) {
                var name = spec.state().name();
                switch (spec) {
                    case StepState step -> checkExit(step.exit(), "step " + name);
                    case AwaitState await -> checkAwait(await, payloads);
                    case TerminalState ignored -> { }
                }
            }
            if (fallbackState != null) checkDeclared(fallbackState, "fallbackState");
            return new ProcessSpec(type, description, initial, states, stateDescriptions, errorHandler, fallbackState);
        }

        private void checkAwait(AwaitState await, Map<String, Class<?>> payloads) {
            var where = "await state " + await.state().name();
            if (await.messages().isEmpty() && await.every() == null && await.timeout() == null) {
                throw new IllegalArgumentException(where + " of process " + type.name()
                    + " has no message, every or timeout");
            }
            var hasExit = await.timeout() != null;
            for (var message : await.messages()) {
                var existing = payloads.putIfAbsent(message.type().name(), message.type().clazz());
                if (existing != null && !existing.equals(message.type().clazz())) {
                    throw new IllegalArgumentException("Message name has conflicting payload classes: "
                        + message.type().name());
                }
                checkExit(message.exit(), "message " + message.type().name() + " in " + where);
                hasExit |= leaves(message.exit(), await.state());
            }
            if (await.every() != null) {
                checkExit(await.every().exit(), "every in " + where);
                hasExit |= leaves(await.every().exit(), await.state());
            }
            if (await.timeout() != null) {
                checkDeclared(await.timeout().next(), "timeout in " + where);
            }
            if (!hasExit) {
                throw new IllegalArgumentException(where + " of process " + type.name()
                    + " has no event leading to another state");
            }
        }

        private static boolean leaves(Exit exit, ProcessStateId state) {
            return switch (exit) {
                case Target target -> !target.state().is(state);
                case ProcessSpec.Routes routes -> routes.routes().values().stream().anyMatch(t -> !t.is(state));
            };
        }

        private void checkExit(Exit exit, String where) {
            switch (exit) {
                case Target target -> checkDeclared(target.state(), where);
                case ProcessSpec.Routes routes -> {
                    routes.routes().values().forEach(target -> checkDeclared(target, where));
                    var outcomes = routes.routes().keySet().iterator().next().getDeclaringClass();
                    if (routes.routes().size() != outcomes.getEnumConstants().length) {
                        throw new IllegalArgumentException("Routes of " + where + " of process " + type.name()
                            + " do not cover all constants of " + outcomes.getSimpleName());
                    }
                }
            }
        }

        private void checkDeclared(ProcessStateId target, String where) {
            if (!states.containsKey(target.name())) {
                throw new IllegalArgumentException("Target state " + target.name() + " of " + where + " of process "
                    + type.name() + " is not declared");
            }
        }
    }
}
