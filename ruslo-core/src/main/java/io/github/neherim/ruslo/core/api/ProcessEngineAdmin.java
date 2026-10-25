package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.TimerType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.core.api.view.ApplicationInstanceInfo;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo;
import io.github.neherim.ruslo.core.api.view.ProcessTypeCounts;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceLifecycle;
import io.github.neherim.ruslo.core.runtime.ProcessRuntimeService;
import io.github.neherim.ruslo.core.runtime.worker.WorkerRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Operator and diagnostic operations. Unlike {@link ProcessEngine}, they throw on unexpected process state.
 */
public final class ProcessEngineAdmin {
    private final WorkerRegistry workerRegistry;
    private final ProcessDefinitionRegistry processDefinitionRegistry;
    private final ProcessRuntimeService processRuntimeService;
    private final ApplicationInstanceLifecycle applicationInstanceLifecycle;

    public ProcessEngineAdmin(ProcessEngine processEngine) {
        Objects.requireNonNull(processEngine, "ProcessEngine must not be null");
        this.workerRegistry = processEngine.workerRegistry();
        this.processDefinitionRegistry = processEngine.processDefinitionRegistry();
        this.processRuntimeService = processEngine.processRuntimeService();
        this.applicationInstanceLifecycle = processEngine.applicationInstanceLifecycle();
    }

    public Set<WorkerSpec> listWorkerSpecifications() {
        return workerRegistry.getAllWorkerSpecs();
    }

    public List<ProcessSpecInfo> listProcessSpecifications() {
        return processDefinitionRegistry.getProcessDefinitions();
    }

    /** Returns the number of {@code ACTIVE} and {@code FAILED} processes. */
    public long countIncompleteProcesses() {
        return processRuntimeService.countIncompleteProcesses();
    }

    /**
     * Counts incomplete processes per type, ordered by type name; types without incomplete processes are omitted.
     *
     * @param overdueAt an {@code ACTIVE} process with {@code nextRunAt} at or before this moment counts as overdue
     */
    public List<ProcessTypeCounts> countIncompleteProcessesByType(Instant overdueAt) {
        Objects.requireNonNull(overdueAt, "Overdue moment must not be null");
        return processRuntimeService.countIncompleteProcessesByType(overdueAt);
    }

    /**
     * Returns the registered instances with a running dispatcher, their claimed process count and registration
     * expiry. An empty list means no dispatcher is running.
     */
    public List<ApplicationInstanceInfo> listApplicationInstances() {
        return applicationInstanceLifecycle.instances();
    }

    /**
     * Moves the process to {@code state} as a regular state entry: the process becomes {@code ACTIVE} (also from
     * {@code FAILED}), the message queue and error are cleared, and the dispatcher continues. Entering a terminal
     * state completes the process.
     */
    public void moveProcessToState(UUID processId, ProcessState state) {
        processRuntimeService.moveToState(processId, state);
    }

    /**
     * Queues a message given as a JSON-compatible payload for the current await, also of a {@code FAILED} process.
     *
     * @throws MessageNotExpectedException if the current state does not await this message
     */
    public void sendMessage(UUID processId, String messageTypeName, Object jsonCompatiblePayload) {
        processRuntimeService.sendAdminMessage(processId, messageTypeName, jsonCompatiblePayload);
    }

    public <T> void setProcessVariable(UUID processId, VariableType<T> type, T value) {
        processRuntimeService.setVariable(processId, type, value);
    }

    /**
     * Removes a process variable.
     *
     * @return {@code true} if the variable was set
     */
    public boolean removeProcessVariable(UUID processId, String name) {
        return processRuntimeService.removeVariable(processId, name);
    }

    /**
     * Sets a process timer, as {@link io.github.neherim.ruslo.core.api.definition.ProcessContext#setTimer} does in a
     * handler; a moment of {@code now} fires the waiting {@code at} at once. The status is not checked: a timer of a
     * completed process has no effect, and a {@code FAILED} process or one in a retry backoff is not woken earlier;
     * after resume the timer counts.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     */
    public void setProcessTimer(UUID processId, TimerType timer, Instant moment) {
        processRuntimeService.setTimer(processId, timer, moment);
    }

    /**
     * Cancels a process timer. Unlike {@link #setProcessTimer}, the timer need not be declared: this also removes
     * a timer left by a previous version of the definition, via {@link TimerType#of(String)}.
     *
     * @return {@code true} if the timer was set
     */
    public boolean cancelProcessTimer(UUID processId, TimerType timer) {
        return processRuntimeService.cancelTimer(processId, timer);
    }

}
