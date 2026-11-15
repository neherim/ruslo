package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec.*;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Process instance and atomic operations on its execution state.
 */
public final class ProcessInstance {
    private final UUID id;
    private final String businessKey;
    private final String correlationId;
    private final ProcessVariables variables;
    private final ProcessTimers timers;
    private final ProcessMessages messages;
    private final ProcessSpec spec;
    private final Clock clock;
    private final List<ProcessStateHist> pendingHistory = new ArrayList<>();
    private ProcessStateId state;
    private ProcessStatus status;
    private int failedAttempts;
    private String lastError;
    private Instant nextRunAt;
    private Instant everyDueAt;
    private Instant afterDueAt;

    private ProcessInstance(
            UUID id,
            String businessKey,
            ProcessSpec spec,
            ProcessVariables variables,
            ProcessTimers timers,
            ProcessMessages messages,
            String correlationId,
            Clock clock
    ) {
        this.id = Objects.requireNonNull(id);
        this.businessKey = businessKey;
        this.spec = Objects.requireNonNull(spec);
        this.variables = Objects.requireNonNull(variables);
        this.timers = Objects.requireNonNull(timers);
        this.messages = Objects.requireNonNull(messages);
        this.correlationId = correlationId;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Creates a process in the initial state.
     */
    public static ProcessInstance createNew(
            UUID id,
            String businessKey,
            ProcessSpec spec,
            ProcessVariables variables,
            ProcessTimers timers,
            ProcessMessages messages,
            String correlationId,
            Instant nextRunAt,
            Clock clock
    ) {
        var process = new ProcessInstance(id, businessKey, spec, variables, timers, messages, correlationId, clock);
        process.changeState(spec.getInitialState(), nextRunAt);
        return process;
    }

    public static ProcessInstance loadFromStorage(
            UUID id,
            String businessKey,
            ProcessSpec spec,
            ProcessStateId state,
            ProcessStatus status,
            ProcessVariables variables,
            ProcessTimers timers,
            ProcessMessages messages,
            String correlationId,
            int failedAttempts,
            String lastError,
            Instant nextRunAt,
            Instant everyDueAt,
            Instant afterDueAt,
            Clock clock
    ) {
        var process = new ProcessInstance(id, businessKey, spec, variables, timers, messages, correlationId, clock);
        process.state = state;
        process.status = status;
        process.failedAttempts = failedAttempts;
        process.lastError = lastError;
        process.nextRunAt = nextRunAt;
        process.everyDueAt = everyDueAt;
        process.afterDueAt = afterDueAt;
        return process;
    }

    /**
     * Enters the target state; the single entry path for every transition. A transition to the current state is not
     * a state change: the message queue, the await timers and the history are kept, the error is cleared, a step
     * runs again and an await keeps waiting.
     */
    public void changeState(ProcessState target) {
        changeState(target, clock.instant());
    }

    private void changeState(ProcessState target, Instant now) {
        Objects.requireNonNull(target);
        if (status == ProcessStatus.COMPLETED) {
            throw new ProcessStatusException(id, status, "a completed process cannot change state");
        }
        var targetStateId = target.id();
        var targetStateSpec = spec.getStateSpec(targetStateId);
        if (!targetStateId.equals(state)) {
            messages.clear();
            state = targetStateId;
            pendingHistory.add(new ProcessStateHist(targetStateId, now));
            everyDueAt = null;
            afterDueAt = null;
            if (targetStateSpec instanceof AwaitState await) {
                if (await.every() != null) everyDueAt = now.plus(await.every().interval().firstAfter());
                if (await.after() != null) afterDueAt = now.plus(await.after().delay());
            }
        }
        status = targetStateSpec instanceof TerminalState ? ProcessStatus.COMPLETED : ProcessStatus.ACTIVE;
        resetAttemptsAndError();
        nextRunAt = now;
        updateNextRunAt();
    }

    /**
     * Returns the current await's event for this message type. Empty if the current state does not await it,
     * including a completed process: a terminal state awaits no messages. Status is ignored: a {@code FAILED}
     * process still awaits the messages of its await.
     */
    public Optional<MessageEvent<?>> expectedMessageEvent(MessageType<?> type) {
        return spec.getMessageEvent(type, state);
    }

    /**
     * Queues the message for the current await and updates its next run, subject to {@link #hasFailedAttempt()}.
     * The event comes from {@link #expectedMessageEvent}, so the current state awaits it. A
     * {@code FAILED} process only queues the message; it is handled after resume.
     */
    public void appendMessage(MessageEvent<?> event, Object payload, Instant receivedAt) {
        messages.append(event.type(), payload, receivedAt);
        updateNextRunAt();
    }

    /**
     * Returns the kind of the earliest due await event; on a tie message, {@code every}, {@code after}, {@code at}.
     *
     * @return empty if no event is due
     */
    public Optional<AwaitEventKind> findDueAwaitEventKind() {
        var now = clock.instant();
        return findEarliestAwaitEvent()
            .filter(event -> !event.dueAt().isAfter(now))
            .map(ScheduledAwaitEvent::kind);
    }

    /**
     * Moves the due {@code every} to the first grid point after now before its handler runs, without moving the
     * next run; missed points are skipped. A handler failure discards the change with the process instance.
     */
    public void advanceEveryTimer() {
        var interval = currentAwait().every().interval().period();
        var elapsedPeriods = Duration.between(everyDueAt, clock.instant()).dividedBy(interval);
        everyDueAt = everyDueAt.plus(interval.multipliedBy(elapsedPeriods + 1));
    }

    /**
     * Disables {@code after} before its handler runs, without moving the next run; a handler failure discards the
     * change with the process instance.
     */
    public void disableAfterTimer() {
        afterDueAt = null;
    }

    /**
     * Schedules the step or await to run again after {@code delay} of engine time and records a failed attempt,
     * see {@link #hasFailedAttempt()}.
     */
    public void scheduleRetryAfter(Duration delay, String error) {
        nextRunAt = clock.instant().plus(delay);
        failedAttempts++;
        lastError = Objects.requireNonNull(error);
    }

    /**
     * Stops the process in {@code FAILED} after an error, keeping the failed step or event.
     */
    public void markFailed(String error) {
        failedAttempts++;
        lastError = Objects.requireNonNull(error);
        status = ProcessStatus.FAILED;
        updateNextRunAt();
    }

    /**
     * Resumes a {@code FAILED} process.
     *
     * @return {@code false} if the process is not {@code FAILED}; it is then unchanged
     */
    public boolean resume() {
        if (status != ProcessStatus.FAILED) {
            return false;
        }
        changeState(state);
        return true;
    }

    /**
     * Recomputes the next run from the status and current state, preserving retry backoff and the schedule of a
     * step or removed state.
     */
    private void updateNextRunAt() {
        if (status != ProcessStatus.ACTIVE) {
            nextRunAt = null;
            return;
        }
        if (hasFailedAttempt()) {
            return;
        }
        nextRunAt = switch (spec.findStateSpec(state).orElse(null)) {
            case StepState ignored -> nextRunAt;
            case AwaitState ignored -> findEarliestAwaitEvent().map(ScheduledAwaitEvent::dueAt).orElse(null);
            case TerminalState ignored -> null;
            case null -> nextRunAt;
        };
    }

    private Optional<ScheduledAwaitEvent> findEarliestAwaitEvent() {
        var events = new ArrayList<ScheduledAwaitEvent>(4);
        messages.firstReceivedAt().ifPresent(time -> events.add(new ScheduledAwaitEvent(AwaitEventKind.MESSAGE, time)));
        if (everyDueAt != null) events.add(new ScheduledAwaitEvent(AwaitEventKind.EVERY, everyDueAt));
        if (afterDueAt != null) events.add(new ScheduledAwaitEvent(AwaitEventKind.AFTER, afterDueAt));
        var await = currentAwait();
        if (await != null && await.at() != null) {
            timers.get(await.at().timer().name())
                .ifPresent(time -> events.add(new ScheduledAwaitEvent(AwaitEventKind.AT, time)));
        }
        return events.stream()
            .min(Comparator.comparing(ScheduledAwaitEvent::dueAt)
                .thenComparing(ScheduledAwaitEvent::kind));
    }

    /**
     * Returns the current await; {@code null} for another or a removed state.
     */
    private AwaitState currentAwait() {
        return spec.findStateSpec(state)
            .filter(AwaitState.class::isInstance)
            .map(AwaitState.class::cast)
            .orElse(null);
    }

    /**
     * Clears the previous error.
     */
    private void resetAttemptsAndError() {
        failedAttempts = 0;
        lastError = null;
    }

    /**
     * Returns whether the process has failed attempts that have not been cleared, including in {@code FAILED}
     * and after the retry time has passed. While {@code ACTIVE}, messages and timer changes preserve the scheduled
     * retry time until the attempts are cleared. An await selects its event by the usual rule when it runs,
     * see {@link #findDueAwaitEventKind()}.
     */
    public boolean hasFailedAttempt() {
        return failedAttempts > 0;
    }

    public boolean isTimeToExecute() {
        return status == ProcessStatus.ACTIVE && nextRunAt != null && !nextRunAt.isAfter(clock.instant());
    }

    public StateSpec getCurrentStateSpec() {
        return spec.getStateSpec(state);
    }

    public ProcessSpec getProcessSpec() {
        return spec;
    }

    public ProcessErrorHandler getProcessErrorHandler() {
        return spec.getProcessErrorHandler();
    }

    public UUID getId() {
        return id;
    }

    public Optional<String> getBusinessKey() {
        return Optional.ofNullable(businessKey);
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public ProcessTypeId getType() {
        return spec.getType();
    }

    public ProcessStateId getState() {
        return state;
    }

    /**
     * Returns the current state as declared in the definition, for the public API.
     */
    public ProcessState getDeclaredState() {
        return spec.declaredState(state.name());
    }

    /**
     * Returns the type as declared in the definition, for the public API.
     */
    public ProcessType getDeclaredType() {
        return spec.declaredType();
    }

    public ProcessStatus getStatus() {
        return status;
    }

    public boolean isCompleted() {
        return status == ProcessStatus.COMPLETED;
    }

    /**
     * Returns the current time of the engine clock.
     */
    public Instant now() {
        return clock.instant();
    }

    public ProcessVariables getVariables() {
        return variables;
    }

    public <T> Optional<T> findVariable(String name, Class<T> clazz) {
        return variables.findVariable(name, clazz);
    }

    public void setVariable(String name, Object value) {
        variables.setVariable(name, value);
    }

    public boolean removeVariable(String name) {
        return variables.removeVariable(name);
    }

    public ProcessTimers getTimers() {
        return timers;
    }

    /**
     * Sets the process timer, truncated to microseconds. If the current await waits for it, the next run is updated
     * subject to {@link #hasFailedAttempt()}; a {@code FAILED} process keeps its next run unset. Whether an {@code at}
     * declares the timer is checked by the caller.
     */
    public void setTimer(TimerType timer, Instant moment) {
        timers.set(timer.name(), moment);
        updateNextRunAt();
    }

    /**
     * Cancels the process timer; the next run is updated as in {@link #setTimer}.
     *
     * @return {@code true} if the timer was set
     */
    public boolean cancelTimer(TimerType timer) {
        var cancelled = timers.cancel(timer.name());
        updateNextRunAt();
        return cancelled;
    }

    public Optional<Instant> getTimer(TimerType timer) {
        return timers.get(timer.name());
    }

    public ProcessMessages getMessages() {
        return messages;
    }

    public int getFailedAttempts() {
        return failedAttempts;
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public Optional<Instant> getNextRunAt() {
        return Optional.ofNullable(nextRunAt);
    }

    public Optional<Instant> getEveryDueAt() {
        return Optional.ofNullable(everyDueAt);
    }

    public Optional<Instant> getAfterDueAt() {
        return Optional.ofNullable(afterDueAt);
    }

    /**
     * Returns the worker of the current state; empty for a terminal or removed state.
     */
    public Optional<WorkerId> getWorkerId() {
        return spec.findStateSpec(state).flatMap(stateSpec -> switch (stateSpec) {
            case StepState step -> Optional.of(step.worker());
            case AwaitState ignored -> Optional.of(WorkerId.DEFAULT);
            case TerminalState ignored -> Optional.empty();
        });
    }

    public boolean hasWorker(WorkerId workerId) {
        return getWorkerId().filter(w -> w.equals(workerId)).isPresent();
    }

    List<ProcessStateHist> drainPendingHistoryEntries() {
        var entries = List.copyOf(pendingHistory);
        pendingHistory.clear();
        return entries;
    }

    /**
     * Await event kinds in tie order.
     */
    public enum AwaitEventKind {
        MESSAGE,
        EVERY,
        AFTER,
        AT
    }

    private record ScheduledAwaitEvent(AwaitEventKind kind, Instant dueAt) {
    }

    @Override
    public String toString() {
        return "Process{" + "id=" + id + ", type=" + getType() + ", state=" + state + '}';
    }
}
