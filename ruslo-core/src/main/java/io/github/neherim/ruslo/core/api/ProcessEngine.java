package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.exception.UnsupportedProcessStateException;
import io.github.neherim.ruslo.core.api.view.ProcessQuery;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchScheduler;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceLifecycle;
import io.github.neherim.ruslo.core.management.ManagementService;
import io.github.neherim.ruslo.core.runtime.ProcessRuntimeService;
import io.github.neherim.ruslo.core.runtime.worker.WorkerRegistry;
import io.github.neherim.ruslo.core.search.ProcessSearchService;
import io.github.neherim.ruslo.core.utils.EngineTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.UUID;

/**
 * Engine entry point: lifecycle, process start, message delivery and search.
 * Operations join the caller's transaction if one is open.
 */
public class ProcessEngine implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(ProcessEngine.class);

    private final ProcessSearchService processSearchService;
    private final ProcessDefinitionRegistry processDefinitionRegistry;
    private final WorkerRegistry workerRegistry;
    private final ProcessDispatchScheduler processDispatchScheduler;
    private final ManagementService managementService;
    private final ProcessRuntimeService processRuntimeService;
    private final ApplicationInstanceLifecycle applicationInstanceLifecycle;
    private final EngineTime engineTime;
    private final boolean databaseMigrationEnabled;
    private final boolean dispatcherEnabled;
    private volatile boolean started;

    ProcessEngine(
            ProcessSearchService processSearchService,
            WorkerRegistry workerRegistry,
            ProcessDefinitionRegistry processDefinitionRegistry,
            ProcessDispatchScheduler processDispatchScheduler,
            ManagementService managementService,
            ProcessRuntimeService processRuntimeService,
            ApplicationInstanceLifecycle applicationInstanceLifecycle,
            EngineTime engineTime,
            boolean databaseMigrationEnabled,
            boolean dispatcherEnabled
    ) {
        this.processSearchService = processSearchService;
        this.workerRegistry = workerRegistry;
        this.processDefinitionRegistry = processDefinitionRegistry;
        this.processDispatchScheduler = processDispatchScheduler;
        this.managementService = managementService;
        this.processRuntimeService = processRuntimeService;
        this.applicationInstanceLifecycle = applicationInstanceLifecycle;
        this.engineTime = engineTime;
        this.databaseMigrationEnabled = databaseMigrationEnabled;
        this.dispatcherEnabled = dispatcherEnabled;
    }

    /**
     * Migrates the schema if enabled and starts the dispatcher if enabled.
     *
     * @throws IllegalStateException if already started
     */
    public synchronized ProcessEngine start() {
        log.info("Starting process engine...");
        if (started) {
            throw new IllegalStateException("Process engine already started");
        }
        if (databaseMigrationEnabled) {
            managementService.updateDatabaseSchema();
        }
        if (dispatcherEnabled) {
            applicationInstanceLifecycle.start();
            try {
                processDispatchScheduler.start();
            } catch (RuntimeException ex) {
                applicationInstanceLifecycle.close();
                throw ex;
            }
        }
        this.started = true;
        log.info("Process engine started");
        return this;
    }

    /**
     * Stops the dispatcher and workers and deregisters this instance. The engine must not be used afterwards.
     */
    @Override
    public synchronized void close() {
        if (!started) {
            return;
        }
        log.info("Stopping process engine...");
        this.started = false;
        try {
            processDispatchScheduler.close();
        } finally {
            try {
                workerRegistry.close();
            } finally {
                applicationInstanceLifecycle.close();
            }
        }
        log.info("Process engine stopped");
    }

    public boolean isStarted() {
        return started;
    }

    /**
     * Registers a definition after the engine is built, e.g. when the definition depends on this engine.
     * Register before {@link #start()} so the dispatcher sees all process types from the first poll.
     *
     * @return this engine
     * @throws IllegalArgumentException if the process type or definition class is already registered,
     *                                  or the definition references an unregistered worker
     */
    public ProcessEngine registerDefinition(ProcessDefinition processDefinition) {
        var type = processDefinitionRegistry.register(processDefinition);
        log.info("Process definition registered: {}", type.name());
        return this;
    }

    private void requireStarted() {
        if (!started) {
            throw new IllegalStateException("Process engine is not started");
        }
    }

    /**
     * Returns a builder that starts a process of the given type.
     *
     * @throws IllegalStateException if the engine is not started
     */
    public ProcessStartBuilder newProcess(ProcessType type) {
        requireStarted();
        return processRuntimeService.newProcess(type);
    }

    /**
     * Resumes a {@code FAILED} process by retrying the failed step or await event.
     *
     * @throws ProcessStatusException if the process is not {@code FAILED}
     * @throws IllegalStateException  if the engine is not started
     */
    public void resumeFailedProcess(UUID processId) {
        requireStarted();
        processRuntimeService.resumeFailedProcess(processId, null);
    }

    /**
     * Resumes a {@code FAILED} process at the target state. A terminal target completes the process immediately.
     *
     * @param targetState any state declared in the process definition
     * @throws ProcessStatusException           if the process is not {@code FAILED}
     * @throws UnsupportedProcessStateException if the definition does not declare the state
     * @throws IllegalStateException           if the engine is not started
     */
    public void resumeFailedProcess(UUID processId, ProcessState targetState) {
        requireStarted();
        processRuntimeService.resumeFailedProcess(processId,
                Objects.requireNonNull(targetState, "Target state cannot be null"));
    }

    /**
     * Queues the message for the current await; the dispatcher handles it after commit.
     * <p>
     * Returning means the message is stored, not that it will be handled.
     *
     * @throws MessageNotExpectedException if the current state does not await this type or the process is completed
     * @throws IllegalArgumentException    if no await of the process declares this type
     * @throws IllegalStateException       if the engine is not started
     */
    public <T> void sendMessage(UUID processId, MessageType<T> type, T message) {
        requireStarted();
        processRuntimeService.sendMessage(processId, type, message);
    }

    /**
     * Processes the message in the calling thread, in the caller's transaction or a new one.
     * <p>
     * The message bypasses the queue: earlier messages, {@code every}, {@code after} and {@code at} events
     * are ignored, and a transition to another state drops the queued messages unhandled. If the message targets
     * the current await, a pending retry of a failed event is kept.
     * <p>
     * A handler exception propagates as is and rolls the transaction back: nothing is stored, no retry or
     * error is recorded and {@link ProcessErrorHandler} is not called.
     *
     * @throws MessageNotExpectedException if the current state does not await this type, the process is completed
     *                                     or {@code FAILED}; nothing is stored
     * @throws IllegalArgumentException    if no await of the process declares this type
     * @throws IllegalStateException       if the engine is not started
     */
    public <T> void sendMessageSync(UUID processId, MessageType<T> type, T message) {
        requireStarted();
        processRuntimeService.sendMessageSync(processId, type, message);
    }

    /**
     * Returns a new process search query.
     */
    public ProcessQuery processes() {
        return processSearchService.processes();
    }

    ProcessSearchService processSearchService() {
        return processSearchService;
    }

    WorkerRegistry workerRegistry() {
        return workerRegistry;
    }

    ProcessDefinitionRegistry processDefinitionRegistry() {
        return processDefinitionRegistry;
    }

    ProcessRuntimeService processRuntimeService() {
        return processRuntimeService;
    }

    ApplicationInstanceLifecycle applicationInstanceLifecycle() {
        return applicationInstanceLifecycle;
    }

    EngineTime engineTime() {
        return engineTime;
    }
}
