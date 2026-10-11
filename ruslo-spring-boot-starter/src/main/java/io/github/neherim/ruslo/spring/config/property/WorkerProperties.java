package io.github.neherim.ruslo.spring.config.property;

import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.WorkerSpec;

/**
 * Worker properties.
 */
public class WorkerProperties {

    /**
     * Step execution mode:
     * <ul>
     *  <li>{@code transactional}: the step runs in the engine transaction; its database changes commit atomically
     *      with the transition, and the step may run again after a rollback.</li>
     *  <li>{@code non_transactional}: the step runs outside the engine transaction, at least once.</li>
     * </ul>
     * Spring accepts any case and separator: {@code non_transactional}, {@code non-transactional},
     * {@code NON_TRANSACTIONAL}.
     */
    private WorkerSpec.ExecutionMode executionMode = WorkerSpec.ExecutionMode.TRANSACTIONAL;

    /**
     * Maximum number of steps running concurrently.
     */
    private int maxConcurrency = 10;

    public WorkerSpec.ExecutionMode getExecutionMode() {
        return executionMode;
    }

    public void setExecutionMode(WorkerSpec.ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public WorkerSpec toWorkerSpec(String workerIdString) {
        return new WorkerSpec(new WorkerId(workerIdString), executionMode, maxConcurrency, null, null);
    }
}
