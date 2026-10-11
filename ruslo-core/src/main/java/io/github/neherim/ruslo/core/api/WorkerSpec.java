package io.github.neherim.ruslo.core.api;

import org.jspecify.annotations.Nullable;

import java.util.Objects;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import java.util.concurrent.ExecutorService;

/**
 * Worker that executes process steps.
 *
 * @param maxConcurrency maximum number of steps executed at once
 * @param customExecutor executor for steps; {@code null} means virtual threads
 */
public record WorkerSpec(
        WorkerId workerId,
        ExecutionMode executionMode,
        int maxConcurrency,
        @Nullable ExecutorService customExecutor,
        String description
) {

    public WorkerSpec {
        Objects.requireNonNull(workerId, "Worker id must not be null");
        Objects.requireNonNull(executionMode, "Execution mode must not be null");
        if (maxConcurrency <= 0) {
            throw new IllegalArgumentException("Max concurrency must be greater than 0");
        }
    }

    public static Builder builder(WorkerId workerId) {
        return new Builder(workerId);
    }

    /**
     * Step execution mode. {@code TRANSACTIONAL} runs the step and the transition in one transaction;
     * {@code NON_TRANSACTIONAL} runs the step outside it, so a step may run more than once.
     */
    public enum ExecutionMode {
        TRANSACTIONAL, NON_TRANSACTIONAL
    }

    /** Defaults: {@code TRANSACTIONAL}, {@code maxConcurrency} 10, virtual threads. */
    public static class Builder {
        private final WorkerId workerId;
        private ExecutionMode executionMode = ExecutionMode.TRANSACTIONAL;
        private int maxConcurrency = 10;
        private ExecutorService customExecutor;
        private String description;

        public Builder(WorkerId workerId) {
            this.workerId = Objects.requireNonNull(workerId, "Worker id must not be null");
        }

        public Builder transactional() {
            this.executionMode = ExecutionMode.TRANSACTIONAL;
            return this;
        }

        public Builder nonTransactional() {
            this.executionMode = ExecutionMode.NON_TRANSACTIONAL;
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Runs steps on this executor instead of virtual threads. The application owns its lifecycle;
         * {@link #maxConcurrency(int)} still applies.
         */
        public Builder customExecutor(ExecutorService executorService) {
            this.customExecutor = Objects.requireNonNull(executorService, "ExecutorService must not be null");
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public WorkerSpec build() {
            return new WorkerSpec(workerId, executionMode, maxConcurrency,
                    customExecutor, description);
        }
    }
}
