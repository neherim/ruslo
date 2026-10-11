package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessNotFoundException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessSpecInfo.StateKind;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.runtime.ProcessRuntimeService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Objects;

/**
 * Runs processes synchronously in the calling thread for tests. Each step runs directly: worker routing,
 * concurrency limits and production transaction boundaries are not reproduced.
 */
public class ProcessEngineEmulator {
    public static final int DEFAULT_MAX_STEPS = 1000;
    private final ProcessEngine processEngine;
    private final ProcessRuntimeService runtime;
    private final ProcessDefinitionRegistry processDefinitionRegistry;
    private final Clock clock;
    private final int maxSteps;

    /**
     * Creates an emulator with {@link #DEFAULT_MAX_STEPS}. Time control requires an engine built with
     * {@link MutableClock}.
     */
    public ProcessEngineEmulator(ProcessEngine processEngine) {
        this(processEngine, DEFAULT_MAX_STEPS);
    }

    /**
     * @param maxSteps step limit of a single {@code runUntil*} call
     */
    public ProcessEngineEmulator(ProcessEngine processEngine, int maxSteps) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.processEngine = Objects.requireNonNull(processEngine, "ProcessEngine must not be null");
        this.runtime = processEngine.processRuntimeService();
        this.processDefinitionRegistry = processEngine.processDefinitionRegistry();
        this.clock = processEngine.clock();
        this.maxSteps = maxSteps;
    }

    /**
     * Executes steps until the process is not ready to run: an await without a due event, a future timer or
     * retry, {@code FAILED} or completion. A failure alone does not stop it: a fallback state or a zero-delay
     * retry runs immediately.
     *
     * @throws IllegalStateException if the step limit is exceeded
     */
    public ProcessInstanceDetails runUntilStop(UUID processId) {
        var process = findProcess(processId);
        if (!isReadyToRun(process)) {
            return process;
        }
        for (int i = 0; i < maxSteps; i++) {
            if (!runtime.forceExecuteOneStep(processId)) {
                return findProcess(processId);
            }
        }
        throw new IllegalStateException(
            "Process " + processId + " did not stop after " + maxSteps + " steps. "
                + "Possible infinite loop in process definition.");
    }

    /**
     * Executes exactly one step or event.
     *
     * @throws ProcessStatusException if the process is completed or {@code FAILED}
     */
    public ProcessInstanceDetails runOneStep(UUID processId) {
        runtime.forceExecuteOneStep(processId);
        return findProcess(processId);
    }

    /**
     * Executes ready steps until the process enters {@code state}.
     *
     * @throws IllegalStateException if the process stops before reaching {@code state} or the step limit is exceeded
     */
    public ProcessInstanceDetails runUntilState(UUID processId, ProcessState state) {
        var proc = findProcess(processId);
        var ready = isReadyToRun(proc);
        for (int i = 0; i < maxSteps; i++) {
            if (proc.state().is(state)) {
                return proc;
            }
            if (!ready) {
                throw new IllegalStateException(
                    "Process " + processId + " stopped in state " + proc.state().name()
                        + " before reaching state " + state.name() + ".");
            }
            ready = runtime.forceExecuteOneStep(processId);
            proc = findProcess(processId);
        }
        throw new IllegalStateException(
            "Process " + processId + " did not reach state " + state.name()
                + " after " + maxSteps + " steps. Possible infinite loop in process definition.");
    }

    /**
     * Sends the message, then runs as {@link #runUntilStop(UUID)}.
     *
     * @throws MessageNotExpectedException if the current state does not await this message or the process is completed
     */
    public <T> ProcessInstanceDetails sendMessageAndRunUntilStop(UUID processId, MessageType<T> type, T message) {
        processEngine.sendMessage(processId, type, message);
        return runUntilStop(processId);
    }

    /**
     * Advances the engine clock. Due timers and retries run on the next emulator call.
     *
     * @throws IllegalStateException if the engine clock is not a {@link MutableClock}
     */
    public void fastForwardTime(Duration duration) {
        mutableClock().fastForwardTime(duration);
    }

    /**
     * Sets the engine clock.
     *
     * @throws IllegalStateException if the engine clock is not a {@link MutableClock}
     */
    public void setTime(Instant time) {
        mutableClock().setTime(time);
    }

    /** Resets the engine clock to its initial time; does nothing if it is not a {@link MutableClock}. */
    public void resetTime() {
        if (clock instanceof MutableClock mutableClock) {
            mutableClock.reset();
        }
    }

    private MutableClock mutableClock() {
        if (clock instanceof MutableClock mutableClock) {
            return mutableClock;
        }
        throw new IllegalStateException(
            "Time can be changed only when the engine is created with MutableClock, but was " + clock);
    }

    private ProcessInstanceDetails findProcess(UUID processId) {
        return processEngine.processes().byId(processId).singleDetails()
            .orElseThrow(() -> new ProcessNotFoundException(processId));
    }

    private boolean isReadyToRun(ProcessInstanceDetails process) {
        if (process.status() != ProcessStatus.ACTIVE) {
            return false;
        }
        return isAwait(process)
            || process.scheduledAt().isPresent() && !process.scheduledAt().get().isAfter(clock.instant());
    }

    private boolean isAwait(ProcessInstanceDetails process) {
        return processDefinitionRegistry.getProcessDefinitions().stream()
            .filter(spec -> spec.type().equals(process.type()))
            .flatMap(spec -> spec.states().stream())
            .anyMatch(state -> state.state().equals(process.state()) && state.kind() == StateKind.AWAIT);
    }


    /**
     * Runs every {@code ACTIVE} process as {@link #runUntilStop(UUID)}; useful when processes interact.
     */
    public List<ProcessInstanceDetails> runAll() {
        return processEngine.processes()
            .byStatus(ProcessStatus.ACTIVE)
            .list().stream()
            .map(p -> runUntilStop(p.id()))
            .toList();
    }
}
