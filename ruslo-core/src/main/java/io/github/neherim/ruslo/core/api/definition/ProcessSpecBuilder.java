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
 * Builds a {@link ProcessSpec}: process settings first, then states.
 * <p>
 * A handler reports a result, the definition declares the target state. A target equal to the current state is not
 * a state change: the message queue, the {@code every} and {@code after} timers and the state history are kept. A
 * step runs again; an await keeps waiting for its events.
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
            draft.giveUpState = Draft.id(state);
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

        /**
         * Declares a step that moves to {@code next} on success. The step runs on the process worker, see
         * {@link TypedBuilder#defaultWorker(WorkerId)}.
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
            }, new Target(Draft.id(next)));
            return this;
        }

        /**
         * Declares a step whose outcome selects the target in {@code routes}. The step runs on the process worker,
         * see {@link TypedBuilder#defaultWorker(WorkerId)}.
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
            draft.addStep(state, worker, step::execute, draft.routes(routes, "step " + Draft.id(state).name()));
            return this;
        }

        /** Declares an await and its events. */
        public StatesBuilder await(ProcessState state, Consumer<AwaitBuilder> definition) {
            var id = Draft.id(state);
            var events = new AwaitBuilder(id, draft);
            requireNonNull(definition, "Await definition cannot be null").accept(events);
            draft.add(state, new AwaitState(id, events.messages, events.every, events.after, events.at));
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
        private static final Function<ProcessContext, Enum<?>> NO_HANDLER = context -> null;
        private final ProcessStateId state;
        private final Draft draft;
        private final List<MessageEvent<?>> messages = new ArrayList<>();
        private EveryEvent every;
        private AfterEvent after;
        private AtEvent at;

        private AwaitBuilder(ProcessStateId state, Draft draft) {
            this.state = state;
            this.draft = draft;
        }

        /**
         * Handles the message and moves to {@code next}. If {@code next} is this await, the process keeps waiting
         * and {@code every} and {@code after} are not restarted (see {@link ProcessSpecBuilder}).
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
         * keeps waiting and does not restart {@code every} and {@code after} (see {@link ProcessSpecBuilder}).
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
         * Calls {@code handler} on the {@code interval} schedule and moves to {@code next}. If {@code next} is this
         * await, the process keeps waiting until the next check.
         * <p>
         * Checks follow a grid: entering the await from another state plus {@code firstAfter} plus
         * {@code k * period}. An event that targets this await, this one included, does not restart the grid. A
         * late check does not shift the next ones; missed points are skipped, and the next check is the first point
         * after the check starts.
         */
        public AwaitBuilder every(Interval interval, ProcessTimerHandler handler, ProcessState next) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addEvery(interval, context -> {
                handler.handle(context);
                return null;
            }, new Target(Draft.id(next)));
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
         * Calls {@code handler} on the {@code interval} schedule; its outcome selects the target in {@code routes}.
         * A target equal to this await keeps waiting until the next check. Checks follow the grid described in
         * {@link #every(Interval, ProcessTimerHandler, ProcessState)}.
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
         * Moves to {@code next} {@code delay} after entering the await from another state. An event that targets
         * this await does not restart the delay; to restart it, go through a step that leads back to the await.
         * {@code next} must not be this await: without a handler the event would do nothing.
         */
        public AwaitBuilder after(Duration delay, ProcessState next) {
            return addAfter(delay, NO_HANDLER, withoutHandler(next, "after"));
        }

        /**
         * Calls {@code handler} {@code delay} after entering the await from another state, then moves to
         * {@code next}. An event that targets this await does not restart the delay; to restart it, go through a
         * step that leads back to the await. If {@code next} is this await, the process keeps waiting for the other
         * events and the timer does not fire again.
         */
        public AwaitBuilder after(Duration delay, ProcessTimerHandler handler, ProcessState next) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addAfter(delay, context -> {
                handler.handle(context);
                return null;
            }, new Target(Draft.id(next)));
        }

        /**
         * Calls {@code handler} {@code delay} after entering the await; its outcome selects the target in
         * {@code routes}, as in {@link #after(Duration, ProcessTimerHandler, ProcessState)}.
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
         * Moves to {@code next} when {@code timer} is due. The code sets the timer, and it outlives the await: see
         * {@link TimerType}. {@code next} must not be this await: without a handler the event would do nothing.
         */
        public AwaitBuilder at(TimerType timer, ProcessState next) {
            return addAt(timer, NO_HANDLER, withoutHandler(next, "at"));
        }

        /**
         * Calls {@code handler} when {@code timer} is due, then moves to {@code next}. The code sets the timer, and it
         * outlives the await: see {@link TimerType}. The handler reads the fired moment from the timer; a timer it
         * left unchanged is cancelled after it. If {@code next} is this await, the process waits for the other events
         * and for the timer the handler moved.
         */
        public AwaitBuilder at(TimerType timer, ProcessTimerHandler handler, ProcessState next) {
            requireNonNull(handler, "Timer handler cannot be null");
            return addAt(timer, context -> {
                handler.handle(context);
                return null;
            }, new Target(Draft.id(next)));
        }

        /**
         * Calls {@code handler} when {@code timer} is due; its outcome selects the target in {@code routes}, as in
         * {@link #at(TimerType, ProcessTimerHandler, ProcessState)}.
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

        private Target withoutHandler(ProcessState next, String event) {
            var target = Draft.id(next);
            if (state.is(target)) {
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
        private final ProcessTypeId type;
        private final Map<String, StateSpec> states = new LinkedHashMap<>();
        private final Map<String, String> stateDescriptions = new HashMap<>();
        private String description;
        private WorkerId defaultWorker = WorkerId.DEFAULT;
        private ProcessErrorHandler errorHandler = ProcessErrorHandler.noRetry();
        private ProcessStateId giveUpState;
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

        private void addStep(
                ProcessState state,
                WorkerId worker,
                Function<ProcessContext, Enum<?>> step,
                Exit exit
        ) {
            requireNonNull(worker, "Worker id cannot be null");
            add(state, new StepState(id(state), step, worker, exit));
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
            if (!(states.get(initial.name()) instanceof StepState)
                    && !(states.get(initial.name()) instanceof AwaitState)) {
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
            return new ProcessSpec(type, description, initial, states, stateDescriptions, errorHandler, giveUpState);
        }

        private void checkAwait(AwaitState await, Map<String, Class<?>> payloads) {
            var where = "await state " + await.state().name();
            if (await.messages().isEmpty() && await.every() == null && await.after() == null && await.at() == null) {
                throw new IllegalArgumentException(where + " of process " + type.name()
                        + " has no message, every, after or at");
            }
            var hasExit = false;
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
            if (await.after() != null) {
                checkExit(await.after().exit(), "after in " + where);
                hasExit |= leaves(await.after().exit(), await.state());
            }
            if (await.at() != null) {
                checkExit(await.at().exit(), "at in " + where);
                hasExit |= leaves(await.at().exit(), await.state());
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
