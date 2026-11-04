package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.DefaultProcessContext;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import io.github.neherim.ruslo.core.runtime.process.ProcessMessages.PendingMessage;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.UUIDGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Executes one step or one selected await event and applies its result.
 * A user code failure is thrown as {@link HandlerException}, an engine failure as is.
 */
public class StepExecutor {
    private static final Logger log = LoggerFactory.getLogger(StepExecutor.class);
    private final ProcessMdc processMdc;
    private final ProcessEngineMetrics metrics;
    private final Clock clock;

    public StepExecutor(ProcessMdc processMdc, ProcessEngineMetrics metrics, Clock clock) {
        this.processMdc = processMdc;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Executes one step or the selected await event and applies its result.
     *
     * @return {@code false} if there is nothing to execute
     */
    public boolean executeNextProcessStep(ProcessInstance process) {
        var executedState = process.getState();
        var started = System.nanoTime();
        try (var ignored = processMdc.open(process)) {
            if (process.getStatus() != ProcessStatus.ACTIVE) return false;
            var executed = switch (process.getStateSpec()) {
                case StepState step -> {
                    executeStep(process, step);
                    yield true;
                }
                case AwaitState await -> executeAwait(process, await);
                case TerminalState ignoredSpec -> false;
            };
            if (executed) recordStepExecuted(process, executedState, started);
            return executed;
        } catch (HandlerException ex) {
            recordStepFailed(process, executedState, started);
            throw ex;
        }
    }

    /**
     * Executes the message handler, bypassing the queue: queued messages and due timers of the await are ignored
     * and keep waiting. A target equal to the current await keeps the retry of a failed event and its error, since
     * this message is not that event. The caller checks that the current state awaits the message.
     *
     * @param payload an instance of the message type class, or {@code null}
     */
    public <T> void executeMessage(ProcessInstance process, MessageEvent<T> event, Object payload) {
        var executedState = process.getState();
        var started = System.nanoTime();
        try (var ignored = processMdc.open(process)) {
            var message = new IncomingMessage<>(UUIDGenerator.generate(), event.type(),
                    event.type().clazz().cast(payload), clock.instant());
            var target = executeMessage(process, new DefaultProcessContext(process), event, message);
            if (!process.getState().is(target)) process.enterState(target);
            recordStepExecuted(process, executedState, started);
        } catch (HandlerException ex) {
            recordStepFailed(process, executedState, started);
            throw ex;
        }
    }

    private void executeStep(ProcessInstance process, StepState spec) {
        log.debug("Execute step {}", process.getState());
        var outcome = invokeHandler(() -> spec.step().apply(new DefaultProcessContext(process)));
        process.enterState(target(spec.exit(), outcome, process, "step"));
    }

    /**
     * Executes the event selected by {@link ProcessInstance#dueAwaitEvent()}: the event is
     * {@link ProcessInstance#takeAwaitEvent taken} before its handler, and the handler's target is entered. Without
     * a due event, e.g. after a manual change of {@code next_run_at}, only the next run is recomputed: no attempt
     * and no history row.
     */
    private boolean executeAwait(ProcessInstance process, AwaitState spec) {
        var due = process.dueAwaitEvent();
        if (due.isEmpty()) {
            process.stayInAwait();
            return false;
        }
        var event = due.get();
        var message = process.getMessages().peekFirst();
        process.takeAwaitEvent(event);
        var context = new DefaultProcessContext(process);
        var target = switch (event) {
            case MESSAGE -> executeQueuedMessage(process, context, message.orElseThrow());
            case EVERY -> executeTimer(process, context, spec.every().handler(), spec.every().exit(), "every");
            case AFTER -> executeTimer(process, context, spec.after().handler(), spec.after().exit(), "after");
            case AT -> executeAt(process, context, spec.at());
        };
        process.enterState(target);
        return true;
    }

    /** Executes the timer handler and returns its target. */
    private static ProcessStateId executeTimer(
            ProcessInstance process,
            DefaultProcessContext context,
            Function<ProcessContext, Enum<?>> handler,
            Exit exit,
            String name
    ) {
        var outcome = invokeHandler(() -> handler.apply(context));
        return target(exit, outcome, process, name);
    }

    /**
     * Executes the {@code at} handler and returns its target. The timer stays set while the handler runs, so the
     * handler reads the fired moment; a timer the handler left unchanged is cancelled, so it does not fire again.
     */
    private static ProcessStateId executeAt(ProcessInstance process, DefaultProcessContext context, AtEvent at) {
        var fired = process.getTimer(at.timer());
        var target = executeTimer(process, context, at.handler(), at.exit(), "at");
        if (process.getTimer(at.timer()).equals(fired)) process.cancelTimer(at.timer());
        return target;
    }

    /** Executes the queued message and returns the handler's target. */
    private ProcessStateId executeQueuedMessage(
            ProcessInstance process,
            DefaultProcessContext context,
            PendingMessage pending
    ) {
        var event = process.getProcessSpec()
            .getMessageEvent(pending.type(), process.getState())
            .orElseThrow(() -> new IllegalStateException("Queued message " + pending.type()
                    + " is not expected in state " + process.getState().name()));
        return convertAndExecute(process, context, event, pending);
    }

    /** Converts the stored message to the handler type {@code T} from the definition and executes it. */
    private <T> ProcessStateId convertAndExecute(
            ProcessInstance process,
            DefaultProcessContext context,
            MessageEvent<T> event,
            PendingMessage pending
    ) {
        return executeMessage(process, context, event, process.getMessages().convert(pending, event.type()));
    }

    /** Executes the message handler and returns its target. */
    private <T> ProcessStateId executeMessage(
            ProcessInstance process,
            DefaultProcessContext context,
            MessageEvent<T> event,
            IncomingMessage<T> message
    ) {
        var outcome = invokeHandler(() -> event.handler().apply(context, message));
        return target(event.exit(), outcome, process, "message " + event.type().name());
    }

    /**
     * Resolves the target of a linear ({@code outcome == null}) or branching handler.
     * {@code null} from a branching handler is a user code failure: {@link HandlerException}.
     */
    private static ProcessStateId target(Exit exit, Enum<?> outcome, ProcessInstance process, String handler) {
        return switch (exit) {
            case Target target -> target.state();
            case Routes routes -> {
                if (outcome == null) {
                    throw new HandlerException(new IllegalStateException("Branching " + handler + " in state "
                            + process.getState().name() + " of process " + process.getType().name()
                            + " returned null, but an outcome is required"));
                }
                yield Objects.requireNonNull(routes.routes().get(outcome), "No route for outcome " + outcome);
            }
        };
    }

    private void recordStepExecuted(ProcessInstance process, ProcessStateId executedState, long started) {
        metrics.recordStepExecuted(process.getType(), executedState, Duration.ofNanos(System.nanoTime() - started));
    }

    private void recordStepFailed(ProcessInstance process, ProcessStateId executedState, long started) {
        metrics.recordStepFailed(process.getType(), executedState, Duration.ofNanos(System.nanoTime() - started));
    }

    /** Calls user code, wrapping its failure in {@link HandlerException}. */
    private static <R> R invokeHandler(Supplier<R> handler) {
        try {
            return handler.get();
        } catch (RuntimeException ex) {
            throw new HandlerException(ex);
        }
    }
}
