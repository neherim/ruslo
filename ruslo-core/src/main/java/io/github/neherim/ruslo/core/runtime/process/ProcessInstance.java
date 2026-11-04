package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec.*;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Process instance and atomic operations on its execution state. */
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
    private int attempt;
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

    /** Creates a process in the initial state. */
    public static ProcessInstance createNew(
            UUID id,
            String businessKey,
            ProcessSpec spec,
            ProcessVariables variables,
            ProcessTimers timers,
            ProcessMessages messages,
            String correlationId,
            Instant startAt,
            Clock clock
    ) {
        var process = new ProcessInstance(id, businessKey, spec, variables, timers, messages, correlationId, clock);
        process.enterStateAt(spec.getInitialState(), startAt);
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
            int attempt,
            String lastError,
            Instant nextRunAt,
            Instant everyDueAt,
            Instant afterDueAt,
            Clock clock
    ) {
        var process = new ProcessInstance(id, businessKey, spec, variables, timers, messages, correlationId, clock);
        process.state = state;
        process.status = status;
        process.attempt = attempt;
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
    public void enterState(ProcessState target) {
        enterStateAt(target, clock.instant());
    }

    private void enterStateAt(ProcessState target, Instant now) {
        Objects.requireNonNull(target);
        if (status == ProcessStatus.COMPLETED) {
            throw new ProcessStatusException(id, status, "a completed process cannot change state");
        }
        var targetId = ProcessState.of(target.name());
        var behavior = spec.getStateSpec(targetId);
        if (state != null && state.is(targetId)) {
            status = ProcessStatus.ACTIVE;
            resetAttemptsAndError();
            if (behavior instanceof StepState) nextRunAt = now;
            else
                recomputeNextRun();
            return;
        }
        messages.clear();
        state = targetId;
        pendingHistory.add(new ProcessStateHist(targetId, now));
        resetAttemptsAndError();
        everyDueAt = null;
        afterDueAt = null;
        switch (behavior) {
            case StepState ignored -> {
                status = ProcessStatus.ACTIVE;
                nextRunAt = now;
            }
            case AwaitState await -> {
                status = ProcessStatus.ACTIVE;
                if (await.every() != null) everyDueAt = now.plus(await.every().interval().firstAfter());
                if (await.after() != null) afterDueAt = now.plus(await.after().delay());
                recomputeNextRun();
            }
            case TerminalState ignored -> {
                status = ProcessStatus.COMPLETED;
                nextRunAt = null;
            }
        }
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
     * Queues the message for the current await and updates its next run, except during a {@link #hasRetryBackoff()
     * retry backoff}. The event comes from {@link #expectedMessageEvent}, so the current state awaits it. A
     * {@code FAILED} process only queues the message; it is handled after resume.
     */
    public void appendMessage(MessageEvent<?> event, Object payload, UUID messageId, Instant receivedAt) {
        messages.append(event.type(), payload, messageId, receivedAt);
        if (status == ProcessStatus.ACTIVE && !hasRetryBackoff()) recomputeNextRun();
    }

    /**
     * Selects the await event to handle now: the due event with the earliest time; on a tie message, {@code every},
     * {@code after}, {@code at}.
     *
     * @return empty if no event is due
     */
    public Optional<AwaitEvent> dueAwaitEvent() {
        var now = clock.instant();
        var times = new EnumMap<AwaitEvent, Instant>(AwaitEvent.class);
        messages.peekFirst().ifPresent(message -> times.put(AwaitEvent.MESSAGE, message.receivedAt()));
        times.putAll(timerMoments());
        return times.entrySet()
            .stream()
            .filter(event -> !event.getValue().isAfter(now))
            .min(Map.Entry.<AwaitEvent, Instant>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(Map.Entry::getKey);
    }

    /**
     * Takes the selected await event before its handler runs, without moving the next run: removes the first queued
     * message, moves {@code every} to the first grid point after now (missed points are skipped), turns {@code after}
     * off while the process stays in the await. The {@code at} timer is kept for its handler and cancelled after it
     * by the caller, unless the handler changed it.
     * <p>
     * Leaving the await resets all of this anyway; a handler failure discards it, since the process is then neither
     * stored nor committed.
     */
    public void takeAwaitEvent(AwaitEvent event) {
        switch (event) {
            case MESSAGE -> messages.removeFirst();
            case EVERY -> {
                var interval = awaitState().every().interval().period();
                var missed = Duration.between(everyDueAt, clock.instant()).dividedBy(interval);
                everyDueAt = everyDueAt.plus(interval.multipliedBy(missed + 1));
            }
            case AFTER -> afterDueAt = null;
            case AT -> {
                // kept for its handler, see the method Javadoc
            }
        }
    }

    /**
     * Finishes an await run without a due event, e.g. after a manual change of {@code next_run_at}: clears the errors
     * of previous attempts and recomputes the next run, so a retry whose event is no longer due is dropped.
     */
    public void stayInAwait() {
        resetAttemptsAndError();
        recomputeNextRun();
    }

    /**
     * Runs the step or await again after {@code delay} of engine time; in an await this starts a
     * {@link #hasRetryBackoff() retry backoff}.
     */
    public void scheduleRetryAfter(Duration delay, String error) {
        nextRunAt = clock.instant().plus(delay);
        attempt++;
        lastError = Objects.requireNonNull(error);
    }

    /** Stops the process in {@code FAILED} after an error, keeping the failed step or event. */
    public void markFailed(String error) {
        attempt++;
        lastError = Objects.requireNonNull(error);
        status = ProcessStatus.FAILED;
        nextRunAt = null;
    }

    /**
     * Resumes a {@code FAILED} process.
     *
     * @return {@code false} if the process is not {@code FAILED}; it is then unchanged
     */
    public boolean resume() {
        if (status != ProcessStatus.FAILED) return false;
        status = ProcessStatus.ACTIVE;
        resetAttemptsAndError();
        if (getStateSpec() instanceof StepState) nextRunAt = clock.instant();
        else
            recomputeNextRun();
        return true;
    }

    /** Recomputes when the active await runs next: the earliest of its timers and the first queued message. */
    private void recomputeNextRun() {
        nextRunAt = Stream.concat(timerMoments().values().stream(), messages.firstReceivedAt().stream())
            .min(Comparator.naturalOrder())
            .orElse(null);
    }

    /** Returns the moments of the enabled timers of the current await; empty for a step or terminal state. */
    private Map<AwaitEvent, Instant> timerMoments() {
        var moments = new EnumMap<AwaitEvent, Instant>(AwaitEvent.class);
        if (everyDueAt != null) moments.put(AwaitEvent.EVERY, everyDueAt);
        if (afterDueAt != null) moments.put(AwaitEvent.AFTER, afterDueAt);
        var await = currentAwait();
        if (await != null && await.at() != null) {
            timers.get(await.at().timer().name()).ifPresent(moment -> moments.put(AwaitEvent.AT, moment));
        }
        return moments;
    }

    /**
     * Updates the next run after a change of {@code timer} if the current await waits for it; the next run is not
     * moved in {@code FAILED} or during a {@link #hasRetryBackoff() retry backoff}.
     */
    private void timerChanged(TimerType timer) {
        var await = currentAwait();
        if (await == null || await.at() == null || !await.at().timer().equals(timer)) return;
        if (status == ProcessStatus.ACTIVE && !hasRetryBackoff()) recomputeNextRun();
    }

    private AwaitState awaitState() {
        return (AwaitState) getStateSpec();
    }

    /** Returns the current await; {@code null} for another or a removed state. */
    private AwaitState currentAwait() {
        return spec.hasState(state) && getStateSpec() instanceof AwaitState await ? await : null;
    }

    /** Clears the previous error. */
    private void resetAttemptsAndError() {
        attempt = 0;
        lastError = null;
    }

    /**
     * Returns whether the process waits for a retry after an error. {@code nextRunAt} is then the retry time: new
     * messages and moved timers do not wake the process before it. After the backoff an await selects its event by
     * the usual rule, see {@link #dueAwaitEvent()}.
     */
    public boolean hasRetryBackoff() {
        return attempt > 0;
    }

    public boolean isTimeToExecute() {
        return status == ProcessStatus.ACTIVE && nextRunAt != null && !nextRunAt.isAfter(clock.instant());
    }

    public StateSpec getStateSpec() {
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

    public ProcessStatus getStatus() {
        return status;
    }

    public boolean isCompleted() {
        return status == ProcessStatus.COMPLETED;
    }

    /** Returns the current time of the engine clock. */
    public Instant now() {
        return clock.instant();
    }

    public ProcessVariables getVariables() {
        return variables;
    }

    public <T> T getVariable(String name, Class<T> clazz) {
        return variables.getVariable(name, clazz);
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
     * Sets the process timer, truncated to microseconds. If the current await waits for it, the next run moves,
     * except in {@code FAILED} or during a {@link #hasRetryBackoff() retry backoff}. Whether an {@code at} declares
     * the timer is checked by the caller.
     */
    public void setTimer(TimerType timer, Instant moment) {
        timers.set(timer.name(), moment);
        timerChanged(timer);
    }

    /**
     * Cancels the process timer; the next run is updated as in {@link #setTimer}.
     *
     * @return {@code true} if the timer was set
     */
    public boolean cancelTimer(TimerType timer) {
        var cancelled = timers.cancel(timer.name());
        if (cancelled) timerChanged(timer);
        return cancelled;
    }

    public Optional<Instant> getTimer(TimerType timer) {
        return timers.get(timer.name());
    }

    public ProcessMessages getMessages() {
        return messages;
    }

    public int getAttempt() {
        return attempt;
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

    /** Returns the worker of the current state; empty for a terminal or removed state. */
    public Optional<WorkerId> getWorkerId() {
        if (!spec.hasState(state) || getStateSpec() instanceof TerminalState) return Optional.empty();
        return Optional.of(spec.getWorkerId(state));
    }

    List<ProcessStateHist> drainPendingHistoryEntries() {
        var entries = List.copyOf(pendingHistory);
        pendingHistory.clear();
        return entries;
    }

    /** Await event kinds in tie order. */
    public enum AwaitEvent {
        MESSAGE,
        EVERY,
        AFTER,
        AT
    }

    @Override
    public String toString() {
        return "Process{" + "id=" + id + ", type=" + getType() + ", state=" + state + '}';
    }
}
