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
import java.util.function.Supplier;
import java.util.stream.Stream;

/** Process instance and atomic operations on its execution state. */
public final class ProcessInstance {
    private final UUID id;
    private final String businessKey;
    private final String correlationId;
    private final ProcessVariables variables;
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
    private Instant atDueAt;

    private ProcessInstance(
            UUID id,
            String businessKey,
            ProcessSpec spec,
            ProcessVariables variables,
            ProcessMessages messages,
            String correlationId,
            Clock clock
    ) {
        this.id = Objects.requireNonNull(id);
        this.businessKey = businessKey;
        this.spec = Objects.requireNonNull(spec);
        this.variables = Objects.requireNonNull(variables);
        this.messages = Objects.requireNonNull(messages);
        this.correlationId = correlationId;
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * Creates a process in the initial state.
     *
     * @throws IllegalArgumentException if a variable used by an {@code at} timer cannot be read as {@link Instant}
     */
    public static ProcessInstance createNew(
            UUID id,
            String businessKey,
            ProcessSpec spec,
            ProcessVariables variables,
            ProcessMessages messages,
            String correlationId,
            Instant startAt,
            Clock clock
    ) {
        for (var name : spec.getAtVariableNames()) {
            checkAtVariable(name, () -> variables.getVariable(name, Instant.class));
        }
        var process = new ProcessInstance(id, businessKey, spec, variables, messages, correlationId, clock);
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
            ProcessMessages messages,
            String correlationId,
            int attempt,
            String lastError,
            Instant nextRunAt,
            Instant everyDueAt,
            Instant afterDueAt,
            Instant atDueAt,
            Clock clock
    ) {
        var process = new ProcessInstance(id, businessKey, spec, variables, messages, correlationId, clock);
        process.state = state;
        process.status = status;
        process.attempt = attempt;
        process.lastError = lastError;
        process.nextRunAt = nextRunAt;
        process.everyDueAt = everyDueAt;
        process.afterDueAt = afterDueAt;
        process.atDueAt = atDueAt;
        return process;
    }

    /** Enters the target state; the single entry path for every transition. */
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
        messages.clear();
        state = targetId;
        pendingHistory.add(new ProcessStateHist(targetId, now));
        resetAttemptsAndError();
        everyDueAt = null;
        afterDueAt = null;
        atDueAt = null;
        switch (behavior) {
            case StepState ignored -> {
                status = ProcessStatus.ACTIVE;
                nextRunAt = now;
            }
            case AwaitState await -> {
                status = ProcessStatus.ACTIVE;
                if (await.every() != null) everyDueAt = now.plus(await.every().initialDelay());
                if (await.after() != null) afterDueAt = now.plus(await.after().delay());
                if (await.at() != null) atDueAt = variables.getVariable(await.at().variable().name(), Instant.class);
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

    /** Removes a handled message and clears the errors of its previous attempts. */
    public void consumeMessage(UUID messageId) {
        messages.remove(messageId);
        resetAttemptsAndError();
        recomputeNextRun();
    }

    /**
     * Selects the await event to handle now: the due event with the earliest time; on a tie message, {@code every},
     * {@code after}, {@code at}.
     *
     * @return empty if no event is due
     */
    public Optional<DueEvent> dueAwaitEvent() {
        var now = clock.instant();
        var times = new EnumMap<AwaitEvent, Instant>(AwaitEvent.class);
        messages.peekFirst().ifPresent(message -> times.put(AwaitEvent.MESSAGE, message.receivedAt()));
        times.putAll(timerMoments());
        return times.entrySet()
            .stream()
            .filter(event -> !event.getValue().isAfter(now))
            .min(Map.Entry.<AwaitEvent, Instant>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
            .map(event -> new DueEvent(event.getKey(), event.getValue()));
    }

    /**
     * Finishes a timer handled with the await itself as the target and clears the errors of previous attempts:
     * {@code every} moves to its next grid point (missed points are skipped), {@code after} is off until the await is
     * entered again, {@code at} is off until its variable gets a new value; a new value written by the handler stays.
     */
    public void timerFired(DueEvent event) {
        switch (event.type()) {
            case EVERY -> {
                var interval = awaitState().every().interval();
                var missed = Duration.between(everyDueAt, clock.instant()).dividedBy(interval);
                everyDueAt = everyDueAt.plus(interval.multipliedBy(missed + 1));
            }
            case AFTER -> afterDueAt = null;
            case AT -> {
                if (event.moment().equals(atDueAt)) atDueAt = null;
            }
            case MESSAGE -> throw new IllegalArgumentException("Not a timer: " + event.type());
        }
        resetAttemptsAndError();
        recomputeNextRun();
    }

    /**
     * Recomputes the next run when the process was taken with no due event, e.g. after a manual change of
     * {@code next_run_at}. A retry whose event is no longer due is dropped.
     */
    public void skipRun() {
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
        if (atDueAt != null) moments.put(AwaitEvent.AT, atDueAt);
        return moments;
    }

    /** Returns whether {@code name} is the {@code at} variable of the current await. */
    private boolean isCurrentAtVariable(String name) {
        var await = currentAwait();
        return await != null && await.at() != null && await.at().variable().name().equals(name);
    }

    /**
     * Moves the {@code at} timer; the next run is not moved in {@code FAILED} or during a
     * {@link #hasRetryBackoff() retry backoff}.
     */
    private void moveAtTimer(Instant moment) {
        atDueAt = moment;
        if (status == ProcessStatus.ACTIVE && !hasRetryBackoff()) recomputeNextRun();
    }

    /** Checks the value of an {@code at} variable before it is stored. */
    private static void checkAtVariable(String name, Supplier<Instant> read) {
        try {
            read.get();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Variable " + name + " is used by an at timer and must be an Instant",
                    ex);
        }
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

    /**
     * Sets the variable. A new value of the current await's {@code at} variable moves its timer; the same value
     * leaves it as is.
     *
     * @throws IllegalArgumentException if the variable is used by an {@code at} timer of any await and the value
     *                                  cannot be read as {@link Instant}; the process is then unchanged
     */
    public void setVariable(String name, Object value) {
        if (spec.getAtVariableNames().contains(name))
            checkAtVariable(name, () -> variables.convert(value, Instant.class));
        var changed = !variables.hasValue(name, value);
        variables.setVariable(name, value);
        if (changed && isCurrentAtVariable(name)) moveAtTimer(variables.getVariable(name, Instant.class));
    }

    /** Removes the variable; removing the current await's {@code at} variable turns its timer off. */
    public boolean removeVariable(String name) {
        var removed = variables.removeVariable(name);
        if (removed && isCurrentAtVariable(name)) moveAtTimer(null);
        return removed;
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

    public Optional<Instant> getAtDueAt() {
        return Optional.ofNullable(atDueAt);
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

    /**
     * Await event selected by {@link #dueAwaitEvent()}.
     *
     * @param moment the event time: message receive time or timer moment
     */
    public record DueEvent(AwaitEvent type, Instant moment) {
    }

    @Override
    public String toString() {
        return "Process{" + "id=" + id + ", type=" + getType() + ", state=" + state + '}';
    }
}
