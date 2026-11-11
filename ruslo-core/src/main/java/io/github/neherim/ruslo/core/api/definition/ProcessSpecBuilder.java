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

/**
 * Builds a {@link ProcessSpec}; the engine passes a builder to {@link ProcessDefinition#define}. Calls go in order:
 * {@link #type(ProcessType)}, process settings, {@link TypedBuilder#initialState(ProcessState)}, states,
 * {@link StatesBuilder#build()}.
 * <p>
 * A handler reports a result, the definition declares the target state. A target equal to the current state is not
 * a state change: the message queue, the {@code every} and {@code after} timers and the state history are kept. A
 * step runs again; an await keeps waiting for its events.
 * <p>
 * States are matched by name, see {@link ProcessState}.
 * <p>
 * Methods throw {@link IllegalArgumentException} for an invalid declaration, immediately or in
 * {@link StatesBuilder#build()}.
 */
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
            draft = new Draft(requireNonNull(type, "Process type cannot be null"));
        }

        /** Sets the description shown in the admin UI. */
        public TypedBuilder description(String description) {
            draft.description = description;
            return this;
        }

        /** Sets the worker for steps that do not set their own; the default is {@link WorkerId#DEFAULT}. */
        public TypedBuilder defaultWorker(WorkerId workerId) {
            draft.defaultWorker = requireNonNull(workerId, "Worker id cannot be null");
            return this;
        }

        /** Sets the error handler; the default is {@link ProcessErrorHandler#noRetry()}. */
        public TypedBuilder onError(ProcessErrorHandler handler) {
            draft.errorHandler = requireNonNull(handler, "Process error handler cannot be null");
            return this;
        }

        /**
         * Sets the state entered when the error handler returns {@link ErrorResult#giveUp()}. Without it
         * {@code giveUp()} stops the process in {@code FAILED}.
         */
        public TypedBuilder onGiveUp(ProcessState state) {
            draft.giveUpState = state.id();
            return this;
        }

        /** Sets the initial state (a step or an await) and starts declaring states. */
        public StatesBuilder initialState(ProcessState state) {
            draft.initial = state.id();
            return new StatesBuilder(draft);
        }
    }

    /** State declarations. */
    public static class StatesBuilder {
        private final Draft draft;

        private StatesBuilder(Draft draft) {
            this.draft = draft;
        }

        /**
         * Declares a step that moves to {@code next} on success. The step runs on the default worker of the process,
         * see {@link TypedBuilder#defaultWorker(WorkerId)}.
         */
        public StatesBuilder step(ProcessState state, ProcessStep step, ProcessState next) {
            return step(state, draft.defaultWorker, step, next);
        }

        /** Declares a step that runs on {@code worker} and moves to {@code next} on success. */
        public StatesBuilder step(ProcessState state, WorkerId worker, ProcessStep step, ProcessState next) {
            requireNonNull(step, "Process step cannot be null");
            draft.addStep(state, worker, context -> {
                step.execute(context);
                return null;
            }, new Target(next.id()));
            return this;
        }

        /**
         * Declares a step whose outcome selects the target in {@code routes}. The step runs on the default worker of
         * the process, see {@link TypedBuilder#defaultWorker(WorkerId)}.
         */
        public <O extends Enum<O>> StatesBuilder step(
                ProcessState state,
                BranchingStep<O> step,
                Consumer<Routes<O>> routes
        ) {
            return step(state, draft.defaultWorker, step, routes);
        }

        /** Declares a step that runs on {@code worker}; its outcome selects the target in {@code routes}. */
        public <O extends Enum<O>> StatesBuilder step(
                ProcessState state,
                WorkerId worker,
                BranchingStep<O> step,
                Consumer<Routes<O>> routes
        ) {
            requireNonNull(step, "Process step cannot be null");
            draft.addStep(state, worker, step::execute, draft.routes(routes, "step " + state.name()));
            return this;
        }

        /**
         * Declares an await and its events.
         *
         * @throws IllegalArgumentException if the await has no events
         */
        public StatesBuilder await(ProcessState state, Consumer<AwaitBuilder> definition) {
            var events = new AwaitBuilder(state, draft);
            requireNonNull(definition, "Await definition cannot be null").accept(events);
            draft.add(state, events.build());
            return this;
        }

        /** Declares a terminal state: entering it completes the process. */
        public StatesBuilder terminal(ProcessState state) {
            draft.add(state, new TerminalState(state));
            return this;
        }

        /**
         * Validates the declarations and builds the spec.
         *
         * @throws IllegalArgumentException if a target state is not declared, the initial state is not a step or an
         *         await, or one message name has different payload classes
         */
        public ProcessSpec build() {
            return draft.build();
        }
    }

    /**
     * Outcome-to-target routes of a branching step or event; must route every constant of {@code O}. Completeness is
     * checked when the routes callback returns.
     */
    public static class Routes<O extends Enum<O>> {
        private final String where;
        private final Map<Enum<?>, Route> routes = new LinkedHashMap<>();

        private Routes(String where) {
            this.where = where;
        }

        /** Routes {@code outcome} to {@code target}. */
        public Routes<O> on(O outcome, ProcessState target) {
            return add(outcome, target, null);
        }

        /**
         * Routes {@code outcome} to {@code target} with a label shown instead of the outcome name in the diagram.
         *
         * @throws IllegalArgumentException if {@code label} is blank
         * @throws NullPointerException if {@code label} is null
         */
        public Routes<O> on(O outcome, ProcessState target, String label) {
            requireNonNull(label, "Route label cannot be null");
            if (label.isBlank()) {
                throw new IllegalArgumentException("Route label cannot be blank");
            }
            return add(outcome, target, label);
        }

        private Routes<O> add(O outcome, ProcessState target, String label) {
            requireNonNull(outcome, "Outcome cannot be null");
            if (outcome instanceof ProcessState) {
                throw new IllegalArgumentException("Outcome " + outcome + " in " + where
                        + " is a process state: the target state is the second argument of on(...)");
            }
            if (routes.putIfAbsent(outcome, new Route(target.id(), label)) != null) {
                throw new IllegalArgumentException("Duplicate outcome " + outcome + " in " + where);
            }
            return this;
        }
    }

    /**
     * Events of one await: any number of messages with distinct names and at most one {@code every}, {@code after}
     * and {@code at}. At least one event is required; all events may target this await.
     */
    public static class AwaitBuilder {
        private static final Function<ProcessContext, Enum<?>> NO_HANDLER = context -> null;
        private final ProcessState state;
        private final Draft draft;
        private final List<MessageEvent<?>> messages = new ArrayList<>();
        private EveryEvent every;
        private AfterEvent after;
        private AtEvent at;

        private AwaitBuilder(ProcessState state, Draft draft) {
            this.state = state;
            this.draft = draft;
        }

        private AwaitState build() {
            if (messages.isEmpty() && every == null && after == null && at == null) {
                throw new IllegalArgumentException(where() + " has no message, every, after or at");
            }
            return new AwaitState(state, messages, every, after, at);
        }

        /**
         * Declares a message event: calls {@code handler} and moves to {@code next}. If {@code next} is this await,
         * the process keeps waiting.
         */
        public <T> AwaitBuilder message(MessageType<T> type, ProcessMessageHandler<T> handler, ProcessState next) {
            requireNonNull(handler, "Message handler cannot be null");
            return addMessage(type, (context, message) -> {
                handler.handle(context, message);
                return null;
            }, new Target(next.id()));
        }

        /**
         * Declares a message event: calls {@code handler}; its outcome selects the target in {@code routes}. A
         * target equal to this await keeps waiting.
         */
        public <T, O extends Enum<O>> AwaitBuilder message(
                MessageType<T> type,
                BranchingMessageHandler<T, O> handler,
                Consumer<Routes<O>> routes
        ) {
            requireNonNull(handler, "Message handler cannot be null");
            return addMessage(type, handler::handle, draft.routes(routes, where("message " + type.name())));
        }

        private <T> AwaitBuilder addMessage(
                MessageType<T> type,
                BiFunction<ProcessContext, IncomingMessage<T>, Enum<?>> handler,
                Exit exit
        ) {
            requireNonNull(type, "Message type cannot be null");
            if (messages.stream().anyMatch(event -> event.type().name().equals(type.name()))) {
                throw new IllegalArgumentException("Duplicate message " + type.name() + " in " + where());
            }
            messages.add(new MessageEvent<>(type, handler, exit));
            return this;
        }

        /** Same as {@link #every(Interval, ProcessTimerHandler, ProcessState)} with {@link Interval#of(Duration)}. */
        public AwaitBuilder every(Duration period, ProcessTimerHandler handler, ProcessState next) {
            return every(Interval.of(period), handler, next);
        }

        /**
         * Declares a periodic event: calls {@code handler} on the {@code interval} schedule and moves to
         * {@code next}. If {@code next} is this await, the process keeps waiting until the next check.
         * <p>
         * Checks follow a grid: entering the await from another state plus {@code firstAfter} plus
         * {@code k * period}. An event that targets this await, this one included, does not restart the grid. A
         * late check does not shift the next ones; missed points are skipped, and the next check is the first point
         * after the check starts. For example, with a period of 10 minutes and the await entered at 12:00, checks are
         * due at 12:10, 12:20 and so on; if the 12:10 check starts at 12:25, the next one is due at 12:30.
         */
        public AwaitBuilder every(Interval interval, ProcessTimerHandler handler, ProcessState next) {
            return addEvery(interval, linear(handler), new Target(next.id()));
        }

        /** Same as {@link #every(Interval, BranchingTimerHandler, Consumer)} with {@link Interval#of(Duration)}. */
        public <O extends Enum<O>> AwaitBuilder every(
                Duration period,
                BranchingTimerHandler<O> handler,
                Consumer<Routes<O>> routes
        ) {
            return every(Interval.of(period), handler, routes);
        }

        /**
         * Declares a periodic event: calls {@code handler} on the {@code interval} schedule; its outcome selects the
         * target in {@code routes}. A target equal to this await keeps waiting until the next check. Checks follow
         * the grid described in {@link #every(Interval, ProcessTimerHandler, ProcessState)}.
         */
        public <O extends Enum<O>> AwaitBuilder every(
                Interval interval,
                BranchingTimerHandler<O> handler,
                Consumer<Routes<O>> routes
        ) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addEvery(interval, handler::handle, draft.routes(routes, where("every")));
        }

        private AwaitBuilder addEvery(Interval interval, Function<ProcessContext, Enum<?>> handler, Exit exit) {
            requireNonNull(interval, "Interval cannot be null");
            if (every != null) throw new IllegalArgumentException("Duplicate every in " + where());
            every = new EveryEvent(interval, handler, exit);
            return this;
        }

        /**
         * Declares a one-time event: moves to {@code next} a positive {@code delay} after entering the await from
         * another state. To restart the delay, go through a step that leads back to the await. {@code next} must not
         * be this await: without a handler the event would do nothing.
         */
        public AwaitBuilder after(Duration delay, ProcessState next) {
            return addAfter(delay, NO_HANDLER, withoutHandler(next, "after"));
        }

        /**
         * Declares a one-time event: calls {@code handler} a positive {@code delay} after entering the await from
         * another state, then moves to {@code next}. To restart the delay, go through a step that leads back to the
         * await. If {@code next} is this await, the process keeps waiting for the other events and the timer does
         * not fire again.
         */
        public AwaitBuilder after(Duration delay, ProcessTimerHandler handler, ProcessState next) {
            return addAfter(delay, linear(handler), new Target(next.id()));
        }

        /**
         * Declares a one-time event: calls {@code handler} {@code delay} after entering the await; its outcome
         * selects the target in {@code routes}, as in {@link #after(Duration, ProcessTimerHandler, ProcessState)}.
         */
        public <O extends Enum<O>> AwaitBuilder after(
                Duration delay,
                BranchingTimerHandler<O> handler,
                Consumer<Routes<O>> routes
        ) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addAfter(delay, handler::handle, draft.routes(routes, where("after")));
        }

        private AwaitBuilder addAfter(Duration delay, Function<ProcessContext, Enum<?>> handler, Exit exit) {
            requirePositive(delay, "after");
            if (after != null) throw new IllegalArgumentException("Duplicate after in " + where());
            after = new AfterEvent(delay, handler, exit);
            return this;
        }

        /**
         * Declares a timer event: moves to {@code next} when {@code timer} is due. The application sets the timer,
         * and it outlives the await: see {@link TimerType}. {@code next} must not be this await: without a handler
         * the event would do nothing.
         */
        public AwaitBuilder at(TimerType timer, ProcessState next) {
            return addAt(timer, NO_HANDLER, withoutHandler(next, "at"));
        }

        /**
         * Declares a timer event: calls {@code handler} when {@code timer} is due, then moves to {@code next}. The
         * application sets the timer, and it outlives the await: see {@link TimerType}. The handler reads the fired
         * moment from the timer and may move it; if the handler leaves the timer unchanged, the engine cancels it
         * after the handler. If {@code next} is this await, the process keeps waiting for the other events and for
         * the new moment of the timer, if the handler set one.
         */
        public AwaitBuilder at(TimerType timer, ProcessTimerHandler handler, ProcessState next) {
            return addAt(timer, linear(handler), new Target(next.id()));
        }

        /**
         * Declares a timer event: calls {@code handler} when {@code timer} is due; its outcome selects the target in
         * {@code routes}, as in {@link #at(TimerType, ProcessTimerHandler, ProcessState)}.
         */
        public <O extends Enum<O>> AwaitBuilder at(
                TimerType timer,
                BranchingTimerHandler<O> handler,
                Consumer<Routes<O>> routes
        ) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addAt(timer, handler::handle, draft.routes(routes, where("at")));
        }

        private AwaitBuilder addAt(TimerType timer, Function<ProcessContext, Enum<?>> handler, Exit exit) {
            requireNonNull(timer, "Timer type cannot be null");
            if (at != null) throw new IllegalArgumentException("Duplicate at in " + where());
            at = new AtEvent(timer, handler, exit);
            return this;
        }

        private static Function<ProcessContext, Enum<?>> linear(ProcessTimerHandler handler) {
            requireNonNull(handler, "Timer handler cannot be null");
            return context -> {
                handler.handle(context);
                return null;
            };
        }

        private Target withoutHandler(ProcessState next, String event) {
            var target = next.id();
            if (state.id().equals(target)) {
                throw new IllegalArgumentException(where(event) + " without a handler cannot target its own await "
                        + "state: it would do nothing");
            }
            return new Target(target);
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
        private final ProcessType declaredType;
        private final ProcessTypeId type;
        private final Map<ProcessStateId, StateSpec> states = new LinkedHashMap<>();
        private String description;
        private WorkerId defaultWorker = WorkerId.DEFAULT;
        private ProcessErrorHandler errorHandler = ProcessErrorHandler.noRetry();
        private ProcessStateId giveUpState;
        private ProcessStateId initial;

        private Draft(ProcessType declaredType) {
            this.declaredType = declaredType;
            this.type = declaredType.id();
        }

        private void add(ProcessState state, StateSpec spec) {
            var id = state.id();
            var name = id.name();
            if (states.putIfAbsent(id, spec) != null) {
                throw new IllegalArgumentException("State " + name + " is already defined in process " + type.name());
            }
            var stateDescription = state.description();
            if (stateDescription == null || stateDescription.isBlank()) {
                throw new IllegalArgumentException("Description of state " + name + " of process " + type.name()
                        + " cannot be blank");
            }
        }

        private void addStep(
                ProcessState state,
                WorkerId worker,
                Function<ProcessContext, Enum<?>> step,
                Exit exit
        ) {
            requireNonNull(worker, "Worker id cannot be null");
            add(state, new StepState(state, step, worker, exit));
        }

        private <O extends Enum<O>> ProcessSpec.Routes routes(Consumer<Routes<O>> definition, String where) {
            var result = new Routes<O>(where + " of process " + type.name());
            requireNonNull(definition, "Routes cannot be null").accept(result);
            if (result.routes.isEmpty()) {
                throw new IllegalArgumentException("Routes of " + result.where + " must contain at least one on(...)");
            }
            var outcomes = result.routes.keySet().iterator().next().getDeclaringClass();
            if (result.routes.size() != outcomes.getEnumConstants().length) {
                throw new IllegalArgumentException("Routes of " + result.where
                        + " do not cover all constants of " + outcomes.getSimpleName());
            }
            return new ProcessSpec.Routes(result.routes);
        }

        private ProcessSpec build() {
            if (!(states.get(initial) instanceof StepState)
                    && !(states.get(initial) instanceof AwaitState)) {
                throw new IllegalArgumentException("Initial state " + initial.name() + " of process " + type.name()
                        + " must be a declared step or await");
            }
            var payloads = new HashMap<String, Class<?>>();
            for (var spec : states.values()) {
                var name = spec.state().name();
                switch (spec) {
                    case StepState step -> checkExit(step.exit(), "step " + name);
                    case AwaitState await -> checkAwait(await, payloads);
                    case TerminalState ignored -> {
                    }
                }
            }
            if (giveUpState != null) checkDeclared(giveUpState, "onGiveUp state");
            return new ProcessSpec(declaredType, description, initial, states, errorHandler, giveUpState);
        }

        private void checkAwait(AwaitState await, Map<String, Class<?>> payloads) {
            var where = "await state " + await.state().name();
            for (var message : await.messages()) {
                var existing = payloads.putIfAbsent(message.type().name(), message.type().clazz());
                if (existing != null && !existing.equals(message.type().clazz())) {
                    throw new IllegalArgumentException("Message name has conflicting payload classes: "
                            + message.type().name());
                }
                checkExit(message.exit(), "message " + message.type().name() + " in " + where);
            }
            if (await.every() != null) {
                checkExit(await.every().exit(), "every in " + where);
            }
            if (await.after() != null) {
                checkExit(await.after().exit(), "after in " + where);
            }
            if (await.at() != null) {
                checkExit(await.at().exit(), "at in " + where);
            }
        }

        private void checkExit(Exit exit, String where) {
            switch (exit) {
                case Target target -> checkDeclared(target.state(), where);
                case ProcessSpec.Routes routes ->
                    routes.routes().values().forEach(route -> checkDeclared(route.state(), where));
            }
        }

        private void checkDeclared(ProcessStateId target, String where) {
            if (!states.containsKey(target)) {
                throw new IllegalArgumentException("Target state " + target.name() + " of " + where + " of process "
                        + type.name() + " is not declared");
            }
        }
    }
}
