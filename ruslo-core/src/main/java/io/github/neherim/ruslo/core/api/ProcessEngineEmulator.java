package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessNotFoundException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.test.MutableTimeSource;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.runtime.ProcessRuntimeService;
import io.github.neherim.ruslo.core.utils.EngineTime;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Runs processes synchronously in the calling thread for tests. Each step runs directly: worker routing,
 * concurrency limits and production transaction boundaries are not reproduced.
 * <p>
 * The emulator checks readiness using the engine time at microsecond precision. To control time, build the
 * engine with a {@link MutableTimeSource} via {@link ProcessEngineBuilder#withTimeSource(RusloTimeSource)}.
 * <p>
 * Operations that take a process id throw {@link ProcessNotFoundException} if no such process exists.
 */
public class ProcessEngineEmulator {
    public static final int DEFAULT_MAX_STEPS = 1000;
    private final ProcessEngine processEngine;
    private final ProcessRuntimeService runtime;
    private final EngineTime engineTime;
    private final int maxSteps;

    /**
     * Creates an emulator with {@link #DEFAULT_MAX_STEPS}.
     */
    public ProcessEngineEmulator(ProcessEngine processEngine) {
        this(processEngine, DEFAULT_MAX_STEPS);
    }

    /**
     * Creates an emulator with a custom step limit.
     *
     * @param maxSteps step limit of a single {@code runUntil*} call
     * @throws IllegalArgumentException if {@code maxSteps} is not positive
     */
    public ProcessEngineEmulator(ProcessEngine processEngine, int maxSteps) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.processEngine = Objects.requireNonNull(processEngine, "ProcessEngine must not be null");
        this.runtime = processEngine.processRuntimeService();
        this.engineTime = processEngine.engineTime();
        this.maxSteps = maxSteps;
    }

    /**
     * Executes steps until the process is not ready to run: an await without a due event, a future timer or
     * retry, {@code FAILED} or completion. A failure alone does not stop it: a give-up state or a zero-delay
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
        throw new IllegalStateException("Process did not stop after " + maxSteps + " steps. "
                + "Possible infinite loop in process definition, see the history:\n" + findProcess(processId));
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
            if (proc.state().name().equals(state.name())) {
                return proc;
            }
            if (!ready) {
                throw new IllegalStateException(
                        "Process stopped before reaching state " + state.name() + ":\n" + proc);
            }
            ready = runtime.forceExecuteOneStep(processId);
            proc = findProcess(processId);
        }
        throw new IllegalStateException("Process did not reach state " + state.name() + " after " + maxSteps
                + " steps. Possible infinite loop in process definition, see the history:\n" + proc);
    }

    /**
     * Sends the message, then runs as {@link #runUntilStop(UUID)}.
     *
     * @throws MessageNotExpectedException if the current state does not await this message or the process is completed
     */
    public <T> ProcessInstanceDetails sendMessageAndRunUntilStop(UUID processId, MessageType<T> type, T message) {
        processEngine.sendMessage(processId, type, message).orThrow();
        return runUntilStop(processId);
    }

    /**
     * Advances the engine's time source. Due timers and retries can run on the next emulator call.
     *
     * @throws IllegalStateException if the source is not a {@link MutableTimeSource}
     */
    public void fastForwardTime(Duration duration) {
        mutableTimeSource().fastForwardTime(duration);
    }

    /**
     * Sets the engine's time source to the given instant.
     *
     * @throws IllegalStateException if the source is not a {@link MutableTimeSource}
     */
    public void setTime(Instant time) {
        mutableTimeSource().setTime(time);
    }

    /** Resets the time source to its initial time if it is a {@link MutableTimeSource}. */
    public void resetTime() {
        if (engineTime.source() instanceof MutableTimeSource source) {
            source.reset();
        }
    }

    private MutableTimeSource mutableTimeSource() {
        if (engineTime.source() instanceof MutableTimeSource source) {
            return source;
        }
        throw new IllegalStateException("Time can be changed only when the engine is created with MutableTimeSource");
    }

    private ProcessInstanceDetails findProcess(UUID processId) {
        return processEngine.processes()
            .byId(processId)
            .singleDetails()
            .orElseThrow(() -> new ProcessNotFoundException(processId));
    }

    private boolean isReadyToRun(ProcessInstanceDetails process) {
        if (process.status() != ProcessStatus.ACTIVE) {
            return false;
        }
        return process.nextRunAt().filter(time -> !time.isAfter(engineTime.now())).isPresent();
    }

    /**
     * Runs every {@code ACTIVE} process as {@link #runUntilStop(UUID)}; useful when processes interact.
     */
    public List<ProcessInstanceDetails> runAll() {
        return processEngine.processes()
            .byStatus(ProcessStatus.ACTIVE)
            .list()
            .stream()
            .map(p -> runUntilStop(p.id()))
            .toList();
    }
}
