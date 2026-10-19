package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessQuery;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchScheduler;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceLifecycle;
import io.github.neherim.ruslo.core.management.ManagementService;
import io.github.neherim.ruslo.core.runtime.ProcessRuntimeService;
import io.github.neherim.ruslo.core.runtime.worker.WorkerRegistry;
import io.github.neherim.ruslo.core.search.ProcessSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;

/**
 * Engine entry point: lifecycle, process start, message delivery and search.
 * <p>
 * Operations join the caller's transaction if one is open. An exception means an error and rolls that
 * transaction back. A rejection caused by concurrent process state ({@link MessageNotExpectedException},
 * {@link ProcessStatusException}) is thrown after the engine's transaction completes, so catching it does not
 * mark the caller's transaction rollback-only.
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
    private final Clock clock;
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
            Clock clock,
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
        this.clock = clock;
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

    /**
     * Registers definitions in iteration order.
     *
     * @return this engine
     * @see #registerDefinition(ProcessDefinition)
     */
    public ProcessEngine registerDefinitions(Collection<? extends ProcessDefinition> processDefinitions) {
        Objects.requireNonNull(processDefinitions, "ProcessDefinitions must not be null")
            .forEach(this::registerDefinition);
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
     * Resumes a {@code FAILED} process: resets the attempt counter and error, so the failed step or await
     * event runs again. Returns once the change is stored; the step runs later on the dispatcher.
     *
     * @throws ProcessStatusException if the process is not {@code FAILED}, e.g. already resumed or completed
     * @throws IllegalStateException  if the engine is not started
     */
    public void resumeFailedProcess(UUID processId) {
        requireStarted();
        processRuntimeService.resumeFailedProcess(processId);
    }

    /**
     * Queues the message for the current await and wakes the dispatcher after commit. A {@code FAILED}
     * process also accepts it; the message is handled after {@link #resumeFailedProcess(UUID)}.
     * <p>
     * Returning means the message is stored, not that it will be handled: an admin transition may close
     * the await and drop the queue. Senders of early external replies need redelivery, e.g. an outbox.
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
     * Runs the message handler in the calling thread, in the caller's transaction or a new one; the process
     * row stays locked until that transaction ends. Subsequent steps run on the dispatcher after commit.
     * <p>
     * The message bypasses the queue: earlier messages and due {@code every}/{@code timeout} events are
     * ignored, and a transition to another state drops the queued messages unhandled.
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

    Clock clock() {
        return clock;
    }
}
