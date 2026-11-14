package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec.*;
import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.DefaultProcessContext;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import io.github.neherim.ruslo.core.runtime.process.ProcessMessages.PendingMessage;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
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
    public boolean executeNext(ProcessInstance process) {
        var executedState = process.getState();
        var started = System.nanoTime();
        try (var ignored = processMdc.open(process)) {
            var executed = switch (process.getCurrentStateSpec()) {
                case StepState step -> executeStep(process, step);
                case AwaitState await -> executeAwait(process, await);
                case TerminalState ignoredSpec -> false;
            };

            if (executed) {
                recordStepExecuted(process, executedState, started);
            }
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
    public <T> void executeMessageBypassingQueue(ProcessInstance process, MessageEvent<T> event, Object payload) {
        var executedState = process.getState();
        var started = System.nanoTime();
        try (var ignored = processMdc.open(process)) {
            log.debug("Starting message {} in {}", event.type().name(), executedState.name());
            var message = new IncomingMessage<>(event.type(), event.type().clazz().cast(payload), clock.instant());
            var context = new DefaultProcessContext(process);
            var outcome = invokeHandler(() -> event.handler().apply(context, message));
            var target = resolveTargetState(event.exit(), outcome, process);
            if (!process.getState().equals(target)) {
                process.enterState(target);
            }
            recordStepExecuted(process, executedState, started);
        } catch (HandlerException ex) {
            recordStepFailed(process, executedState, started);
            throw ex;
        }
    }

    private boolean executeStep(ProcessInstance process, StepState spec) {
        log.debug("Starting step {}", process.getState().name());
        var context = new DefaultProcessContext(process);
        var outcome = invokeHandler(() -> spec.step().apply(context));
        var target = resolveTargetState(spec.exit(), outcome, process);
        process.enterState(target);
        return true;
    }

    /**
     * Executes the selected due event and enters its target. Without a due event, recomputes the next run and clears
     * the previous error, e.g. after a manual change of {@code next_run_at}.
     */
    private boolean executeAwait(ProcessInstance process, AwaitState spec) {
        var due = process.dueAwaitEvent().orElse(null);
        if (due == null) {
            process.stayInAwait();
            return false;
        }
        log.debug("Starting event {} in {}", due, process.getState().name());
        var context = new DefaultProcessContext(process);
        var target = switch (due) {
            case MESSAGE -> executeQueuedMessage(process, context, process.getMessages().takeFirst());
            case EVERY -> executeEveryEvent(process, context, spec.every());
            case AFTER -> executeAfterEvent(process, context, spec.after());
            case AT -> executeAtEvent(process, context, spec.at());
        };
        process.enterState(target);
        return true;
    }

    private static ProcessStateId executeEveryEvent(ProcessInstance process, ProcessContext context, EveryEvent every) {
        process.advanceEveryTimer();
        var outcome = invokeHandler(() -> every.handler().apply(context));
        return resolveTargetState(every.exit(), outcome, process);
    }

    private static ProcessStateId executeAfterEvent(ProcessInstance process, ProcessContext context, AfterEvent after) {
        process.disableAfterTimer();
        var outcome = invokeHandler(() -> after.handler().apply(context));
        return resolveTargetState(after.exit(), outcome, process);
    }

    /**
     * Executes the {@code at} handler and returns its target. The timer stays set while the handler runs, so the
     * handler reads the fired moment; a timer the handler left unchanged is cancelled, so it does not fire again.
     */
    private static ProcessStateId executeAtEvent(ProcessInstance process, ProcessContext context, AtEvent at) {
        var firedAt = process.getTimer(at.timer());
        var outcome = invokeHandler(() -> at.handler().apply(context));
        var target = resolveTargetState(at.exit(), outcome, process);

        if (process.getTimer(at.timer()).equals(firedAt)) {
            process.cancelTimer(at.timer());
        }
        return target;
    }

    /**
     * Executes the queued message and returns the handler's target.
     */
    private ProcessStateId executeQueuedMessage(
            ProcessInstance process,
            ProcessContext context,
            PendingMessage pending
    ) {
        var event = process.getProcessSpec()
            .getMessageEvent(pending.type(), process.getState())
            .orElseThrow(() -> new IllegalStateException("Queued message " + pending.type()
                    + " is not expected in state " + process.getState().name()));
        return executeQueuedMessage(process, context, event, pending);
    }

    /**
     * Converts the queued message, executes its handler and returns the target.
     */
    private <T> ProcessStateId executeQueuedMessage(
            ProcessInstance process,
            ProcessContext context,
            MessageEvent<T> event,
            PendingMessage pending
    ) {
        var message = process.getMessages().convert(pending, event.type());
        var outcome = invokeHandler(() -> event.handler().apply(context, message));
        return resolveTargetState(event.exit(), outcome, process);
    }

    /**
     * Resolves the target of a linear ({@code outcome == null}) or branching handler.
     * {@code null} from a branching handler is a user code failure: {@link HandlerException}.
     */
    private static ProcessStateId resolveTargetState(
            Exit exit,
            Enum<?> outcome,
            ProcessInstance process
    ) {
        return switch (exit) {
            case Target target -> target.state();
            case Routes routes -> {
                if (outcome == null) {
                    var message = "Branching handler in state %s of process %s returned null, but an outcome is required"
                        .formatted(process.getState().name(), process.getType().name());
                    throw new HandlerException(new IllegalStateException(message));
                }
                yield routes.routes().get(outcome).state();
            }
        };
    }

    private void recordStepExecuted(ProcessInstance process, ProcessStateId executedState, long started) {
        log.debug("Execution completed: {} -> {}", executedState.name(), process.getState().name());
        metrics.recordStepExecuted(process.getType(), executedState, Duration.ofNanos(System.nanoTime() - started));
    }

    private void recordStepFailed(ProcessInstance process, ProcessStateId executedState, long started) {
        metrics.recordStepFailed(process.getType(), executedState, Duration.ofNanos(System.nanoTime() - started));
    }

    /**
     * Calls user code, wrapping its failure in {@link HandlerException}.
     */
    private static <R> R invokeHandler(Supplier<R> handler) {
        try {
            return handler.get();
        } catch (RuntimeException ex) {
            throw new HandlerException(ex);
        }
    }
}
