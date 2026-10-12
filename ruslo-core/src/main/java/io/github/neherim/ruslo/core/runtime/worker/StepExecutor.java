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
import java.time.Instant;
import java.util.Objects;
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
     * Executes the message handler, bypassing the queue: queued messages and due {@code every}/{@code timeout}
     * events are ignored and keep waiting, error details are kept. The caller checks that the current state awaits
     * the message.
     *
     * @param payload an instance of the message type class, or {@code null}
     */
    public <T> void executeMessage(ProcessInstance process, MessageEvent<T> event, Object payload) {
        var executedState = process.getState();
        var started = System.nanoTime();
        try (var ignored = processMdc.open(process)) {
            var message = new IncomingMessage<>(UUIDGenerator.generate(), event.type(),
                    event.type().clazz().cast(payload), clock.instant());
            executeMessage(process, new DefaultProcessContext(process), event, message);
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

    private boolean executeAwait(ProcessInstance process, AwaitState spec) {
        var event = selectAwaitEvent(process);
        if (event == null) return false;
        var context = new DefaultProcessContext(process);
        switch (event) {
            case MESSAGE -> executeQueuedMessage(process, context);
            case EVERY -> {
                var outcome = invokeHandler(() -> spec.every().handler().apply(context));
                if (applyAwait(outcome, spec.every().exit(), process, "every")) {
                    process.rescheduleTimer();
                }
            }
            case TIMEOUT -> {
                var timeout = spec.timeout();
                if (timeout.handler() != null) invokeHandler(() -> timeout.handler().handle(context));
                process.enterState(timeout.next());
            }
        }
        return true;
    }

    /** Executes the first queued message and removes it if the process stays in the current await. */
    private void executeQueuedMessage(ProcessInstance process, DefaultProcessContext context) {
        var pending = process.getMessages().peekFirst().orElseThrow();
        var event = process.getProcessSpec()
            .getMessageEvent(pending.type(), process.getState())
            .orElseThrow(() -> new IllegalStateException("Queued message " + pending.type()
                    + " is not expected in state " + process.getState().name()));
        if (convertAndExecute(process, context, event, pending)) process.consumeMessage(pending.id());
    }

    /** Converts the stored message to the handler type {@code T} from the definition and executes it. */
    private <T> boolean convertAndExecute(
            ProcessInstance process,
            DefaultProcessContext context,
            MessageEvent<T> event,
            PendingMessage pending
    ) {
        return executeMessage(process, context, event, process.getMessages().convert(pending, event.type()));
    }

    /** Returns {@code true} if the process stays in the current await. */
    private <T> boolean executeMessage(
            ProcessInstance process,
            DefaultProcessContext context,
            MessageEvent<T> event,
            IncomingMessage<T> message
    ) {
        var outcome = invokeHandler(() -> event.handler().apply(context, message));
        return applyAwait(outcome, event.exit(), process, "message " + event.type().name());
    }

    /**
     * Applies the await event result. A target equal to the current await is not an entry: the process is
     * unchanged, and the caller finishes the event (removes the message, reschedules {@code every}).
     *
     * @return {@code true} if the process stays in the current await
     */
    private static boolean applyAwait(Enum<?> outcome, Exit exit, ProcessInstance process, String event) {
        var target = target(exit, outcome, process, event);
        if (process.getState().is(target)) return true;
        process.enterState(target);
        return false;
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

    /** Calls user code without a result, wrapping its failure in {@link HandlerException}. */
    private static void invokeHandler(Runnable handler) {
        invokeHandler(() -> {
            handler.run();
            return null;
        });
    }

    /**
     * Selects the due event with the earliest time; on a tie: message, {@code every}, {@code timeout}.
     * Event times: message {@code receivedAt}, {@code every} {@code scheduledAt}, {@code timeout} {@code deadlineAt}.
     * Exception: during a retry backoff a due deadline wins over the event awaiting the retry.
     */
    private AwaitEvent selectAwaitEvent(ProcessInstance process) {
        var now = clock.instant();
        var message = process.getMessages().peekFirst().map(PendingMessage::receivedAt).orElse(null);
        var every = process.getScheduledAt().filter(time -> !time.isAfter(now)).orElse(null);
        var timeout = process.getDeadlineAt().filter(time -> !time.isAfter(now)).orElse(null);
        if (timeout != null && process.hasRetryBackoff()) return AwaitEvent.TIMEOUT;
        // Strict isBefore keeps the earlier candidate on equal times.
        AwaitEvent selected = null;
        Instant at = null;
        if (message != null) {
            selected = AwaitEvent.MESSAGE;
            at = message;
        }
        if (every != null && (at == null || every.isBefore(at))) {
            selected = AwaitEvent.EVERY;
            at = every;
        }
        if (timeout != null && (at == null || timeout.isBefore(at))) selected = AwaitEvent.TIMEOUT;
        return selected;
    }

    private enum AwaitEvent {
        MESSAGE,
        EVERY,
        TIMEOUT
    }
}
