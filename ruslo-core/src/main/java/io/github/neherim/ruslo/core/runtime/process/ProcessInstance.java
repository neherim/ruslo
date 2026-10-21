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
import java.util.List;
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
    private final ProcessMessages messages;
    private final ProcessSpec spec;
    private final Clock clock;
    private final List<ProcessStateHist> pendingHistory = new ArrayList<>();
    private ProcessStateId state;
    private ProcessStatus status;
    private int attempt;
    private String lastError;
    private Instant scheduledAt;
    private Instant availableAt;
    private Instant deadlineAt;

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
            Instant scheduledAt,
            Instant availableAt,
            Instant deadlineAt,
            Clock clock
    ) {
        var process = new ProcessInstance(id, businessKey, spec, variables, messages, correlationId, clock);
        process.state = state;
        process.status = status;
        process.attempt = attempt;
        process.lastError = lastError;
        process.scheduledAt = scheduledAt;
        process.availableAt = availableAt;
        process.deadlineAt = deadlineAt;
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
        switch (behavior) {
            case StepState ignored -> {
                status = ProcessStatus.ACTIVE;
                scheduledAt = now;
                availableAt = now;
                deadlineAt = null;
            }
            case AwaitState await -> {
                status = ProcessStatus.ACTIVE;
                scheduledAt = await.every() == null ? null : now.plus(await.every().initialDelay());
                deadlineAt = await.timeout() == null ? null : now.plus(await.timeout().delay());
                recomputeAvailability();
            }
            case TerminalState ignored -> {
                status = ProcessStatus.COMPLETED;
                scheduledAt = null;
                availableAt = null;
                deadlineAt = null;
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
     * Queues the message for the current await and updates its availability. The event comes from
     * {@link #expectedMessageEvent}, so the current state awaits it. A {@code FAILED} process only queues the message;
     * it is handled after resume.
     */
    public void appendMessage(MessageEvent<?> event, Object payload, UUID messageId, Instant receivedAt) {
        messages.append(event.type(), payload, messageId, receivedAt);
        if (status == ProcessStatus.ACTIVE && !hasRetryBackoff()) recomputeAvailability();
    }

    /** Removes a handled message and clears the errors of its previous attempts. */
    public void consumeMessage(UUID messageId) {
        messages.remove(messageId);
        resetAttemptsAndError();
        recomputeAvailability();
    }

    /**
     * Schedules the next {@code every} check at the first point of the await's grid after now, skipping missed points,
     * and clears the errors of its previous attempts.
     */
    public void rescheduleTimer() {
        var interval = ((AwaitState) getStateSpec()).every().interval();
        var missed = Duration.between(scheduledAt, clock.instant()).dividedBy(interval);
        scheduledAt = scheduledAt.plus(interval.multipliedBy(missed + 1));
        resetAttemptsAndError();
        recomputeAvailability();
    }

    /**
     * Schedules a retry of the failed step or event after {@code delay} of engine time. An await deadline that comes
     * earlier wakes the process at the deadline: an event failure does not postpone it.
     */
    public void scheduleRetryAfter(Duration delay, String error) {
        attempt++;
        lastError = Objects.requireNonNull(error);
        var retryAt = clock.instant().plus(delay);
        availableAt = deadlineAt != null && deadlineAt.isBefore(retryAt) ? deadlineAt : retryAt;
    }

    /** Stops the process in {@code FAILED} after an error, keeping the failed step or event. */
    public void markFailed(String error) {
        attempt++;
        lastError = Objects.requireNonNull(error);
        status = ProcessStatus.FAILED;
        availableAt = null;
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
        if (getStateSpec() instanceof StepState) {
            scheduledAt = clock.instant();
            availableAt = scheduledAt;
        } else
            recomputeAvailability();
        return true;
    }

    /** Recomputes when the active await is available: the earliest of timer, deadline and first queued message. */
    private void recomputeAvailability() {
        availableAt = Stream.of(scheduledAt, deadlineAt, messages.firstReceivedAt().orElse(null))
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(null);
    }

    /** Clears the previous error. */
    private void resetAttemptsAndError() {
        attempt = 0;
        lastError = null;
    }

    /**
     * Returns whether the process waits for a retry: {@code availableAt} is then the retry time, not the next event.
     */
    public boolean hasRetryBackoff() {
        return attempt > 0;
    }

    public boolean isTimeToExecute() {
        return status == ProcessStatus.ACTIVE && availableAt != null && !availableAt.isAfter(clock.instant());
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

    public ProcessMessages getMessages() {
        return messages;
    }

    public int getAttempt() {
        return attempt;
    }

    public Optional<String> getLastError() {
        return Optional.ofNullable(lastError);
    }

    public Optional<Instant> getScheduledAt() {
        return Optional.ofNullable(scheduledAt);
    }

    public Optional<Instant> getDeadlineAt() {
        return Optional.ofNullable(deadlineAt);
    }

    public Optional<Instant> getAvailableAt() {
        return Optional.ofNullable(availableAt);
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

    @Override
    public String toString() {
        return "Process{" + "id=" + id + ", type=" + getType() + ", state=" + state + '}';
    }
}
