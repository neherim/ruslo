package io.github.neherim.ruslo.core.runtime;

import io.github.neherim.ruslo.core.api.ProcessStartBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessEngineException;
import io.github.neherim.ruslo.core.api.exception.ProcessNotFoundException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.api.view.ProcessTypeCounts;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchSignal;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceFactory;
import io.github.neherim.ruslo.core.runtime.worker.HandlerException;
import io.github.neherim.ruslo.core.runtime.worker.StepErrorHandler;
import io.github.neherim.ruslo.core.runtime.worker.StepExecutor;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.UUIDGenerator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Runtime operations on process instances behind {@link io.github.neherim.ruslo.core.api.ProcessEngine},
 * {@link io.github.neherim.ruslo.core.api.ProcessEngineAdmin} and
 * {@link io.github.neherim.ruslo.core.api.ProcessEngineEmulator}; the contracts are documented there.
 */
public class ProcessRuntimeService {
    private static final Logger log = LoggerFactory.getLogger(ProcessRuntimeService.class);
    private final Clock clock;
    private final Database database;
    private final ProcessInstanceDao processInstanceDao;
    private final ProcessDispatchSignal dispatchSignal;
    private final ProcessInstanceFactory processInstanceFactory;
    private final ProcessEngineMetrics metrics;
    private final ProcessMdc processMdc;
    private final StepExecutor stepExecutor;
    private final StepErrorHandler stepErrorHandler;

    public ProcessRuntimeService(
            Clock clock,
            Database database,
            ProcessInstanceDao processInstanceDao,
            ProcessInstanceFactory processInstanceFactory,
            ProcessDispatchSignal dispatchSignal,
            ProcessEngineMetrics metrics,
            ProcessMdc processMdc,
            StepExecutor stepExecutor,
            StepErrorHandler stepErrorHandler
    ) {
        this.clock = clock;
        this.database = database;
        this.processInstanceDao = processInstanceDao;
        this.dispatchSignal = dispatchSignal;
        this.processInstanceFactory = processInstanceFactory;
        this.metrics = metrics;
        this.processMdc = processMdc;
        this.stepExecutor = stepExecutor;
        this.stepErrorHandler = stepErrorHandler;
    }

    /**
     * Returns a builder that starts a process of the given type.
     */
    public ProcessStartBuilder newProcess(ProcessType type) {
        return new ProcessStartBuilder(type, this::startProcess);
    }

    /**
     * Resumes a {@code FAILED} process, see {@link io.github.neherim.ruslo.core.api.ProcessEngine#resumeFailedProcess}.
     *
     * @throws ProcessStatusException if the process is not {@code FAILED}; thrown after the transaction
     */
    public void resumeFailedProcess(UUID processId) {
        doInTransactionOrReject(() -> {
            var process = findProcessWithLock(processId);
            if (!process.resume()) {
                return new ProcessStatusException(processId, process.getStatus(),
                        "only a FAILED process can be resumed");
            }
            processInstanceDao.update(process);
            database.doAfterCommit(dispatchSignal::wakeUp);
            return null;
        });
    }

    /**
     * Moves the process to the state as a regular state entry,
     * see {@link io.github.neherim.ruslo.core.api.ProcessEngineAdmin#moveProcessToState}.
     *
     * @throws ProcessStatusException if the process is completed
     */
    public void moveToState(UUID processId, ProcessState processState) {
        database.doInTransaction(() -> {
            var process = findProcessWithLock(processId);
            process.enterState(processState);
            processInstanceDao.update(process);
            database.doAfterCommit(dispatchSignal::wakeUp);
        });
    }

    /**
     * Queues the message for the current await, see {@link io.github.neherim.ruslo.core.api.ProcessEngine#sendMessage}.
     *
     * @throws MessageNotExpectedException if the current state does not await this type or the process is completed;
     *                                     thrown after the transaction
     * @throws IllegalArgumentException    if no await of the process declares this type
     */
    public <T> void sendMessage(UUID processId, MessageType<T> type, T message) {
        doInTransactionOrReject(() -> {
            var process = findProcessWithLock(processId);
            var event = expectedMessageEvent(process, type);
            if (event.isEmpty()) return messageNotExpected(process, type.name());
            appendMessage(process, event.get(), message);
            return null;
        });
    }

    /**
     * Queues a JSON-compatible payload converted to the message class awaited by the current state,
     * see {@link io.github.neherim.ruslo.core.api.ProcessEngineAdmin#sendMessage}.
     *
     * @throws MessageNotExpectedException if the current state does not await this message or the process is completed
     */
    public void sendAdminMessage(UUID processId, String name, Object jsonPayload) {
        database.doInTransaction(() -> {
            var process = findProcessWithLock(processId);
            var event = process.getProcessSpec()
                .getMessageEvent(name, process.getState())
                .orElseThrow(() -> messageNotExpected(process, name));
            appendMessage(process, event, process.getMessages().normalizePayload(jsonPayload, event.type().clazz()));
        });
    }

    /**
     * Runs the message handler in the calling thread,
     * see {@link io.github.neherim.ruslo.core.api.ProcessEngine#sendMessageSync}. A handler exception propagates
     * as is; engine rejections are thrown after the transaction.
     *
     * @throws MessageNotExpectedException if the current state does not await this type, the process is completed
     *                                     or {@code FAILED}
     * @throws IllegalArgumentException    if no await of the process declares this type
     */
    public <T> void sendMessageSync(UUID processId, MessageType<T> type, T message) {
        try {
            doInTransactionOrReject(() -> {
                var process = findProcessWithLock(processId);
                var event = expectedMessageEvent(process, type);
                if (event.isEmpty() || process.getStatus() == ProcessStatus.FAILED) {
                    return messageNotExpected(process, type.name());
                }
                stepExecutor.executeMessage(process, event.get(), message);
                processInstanceDao.update(process);
                database.doAfterCommit(dispatchSignal::wakeUp);
                return null;
            });
        } catch (HandlerException ex) {
            throw ex.getCause();
        }
    }

    /**
     * Executes exactly one step of the process in the calling thread. A step runs at once, even while it waits
     * for a retry; an await runs an event only when one is due (an event or a retry after an error).
     * Leases are ignored: if a non-transactional worker runs the process at the same time, a user step may run twice.
     *
     * @return {@code true} if the process is ready to continue immediately, also after an error when
     *         {@code fallback} or a zero-delay retry leads to a step; {@code false} on completion, {@code FAILED},
     *         an await without a due event, a delayed retry or a step scheduled in the future
     * @throws ProcessStatusException if the process is completed or {@code FAILED}
     */
    public boolean forceExecuteOneStep(UUID processId) {
        var executedState = new AtomicReference<ProcessStateId>();
        try {
            return database.doInTransactionWithResult(() -> {
                var process = findProcessWithLock(processId);
                executedState.set(process.getState());
                if (process.getStatus() != ProcessStatus.ACTIVE) {
                    throw new ProcessStatusException(processId, process.getStatus(),
                            "only an ACTIVE process runs steps");
                }
                if (process.getStateSpec() instanceof ProcessSpec.AwaitState
                        && process.getNextRunAt().map(time -> time.isAfter(clock.instant())).orElse(true)) {
                    return false;
                }
                stepExecutor.executeNextProcessStep(process);
                processInstanceDao.update(process);
                database.doAfterCommit(dispatchSignal::wakeUp);
                return process.isTimeToExecute();
            });
        } catch (HandlerException ex) {
            return stepErrorHandler.handleAdministrativeError(ex.getCause(), processId, executedState.get());
        }
    }

    /**
     * Sets a process variable.
     */
    public <T> void setVariable(UUID processId, VariableType<T> type, T value) {
        database.doInTransaction(() -> {
            var process = findProcessWithLock(processId);
            process.setVariable(type.name(), value);
            processInstanceDao.update(process);
        });
    }

    /**
     * Removes a process variable.
     *
     * @return {@code true} if the variable was set
     */
    public boolean removeVariable(UUID processId, String name) {
        return database.doInTransactionWithResult(() -> {
            var process = findProcessWithLock(processId);
            var removed = process.removeVariable(name);
            if (removed) {
                processInstanceDao.update(process);
            }
            return removed;
        });
    }

    /** Returns the number of {@code ACTIVE} and {@code FAILED} processes. */
    public long countIncompleteProcesses() {
        return processInstanceDao.countIncompleteProcesses();
    }

    /**
     * Counts incomplete processes per type.
     *
     * @param overdueAt an {@code ACTIVE} process with {@code nextRunAt} at or before this moment counts as overdue
     */
    public List<ProcessTypeCounts> countIncompleteProcessesByType(Instant overdueAt) {
        return processInstanceDao.countIncompleteByType(overdueAt);
    }

    private UUID startProcess(
            ProcessType type,
            @Nullable String businessKey,
            @Nullable String correlationId,
            Map<String, Object> variables
    ) {
        var effectiveCorrelationId = correlationId != null && !correlationId.isBlank()
                ? correlationId
                : processMdc.getOrCreateCorrelationId();
        var process = processInstanceFactory.newInstance(type, clock.instant(), businessKey,
                effectiveCorrelationId, variables);

        return database.doInTransactionWithResult(() -> {
            processInstanceDao.insert(process);
            log.debug("Process started {}", process);
            database.doAfterCommit(() -> {
                dispatchSignal.wakeUp();
                metrics.recordProcessStarted(type);
            });
            return process.getId();
        });
    }

    private void appendMessage(ProcessInstance process, ProcessSpec.MessageEvent<?> event, @Nullable Object payload) {
        process.appendMessage(event, payload, UUIDGenerator.generate(), clock.instant());
        processInstanceDao.update(process);
        database.doAfterCommit(dispatchSignal::wakeUp);
    }

    /**
     * Returns the current state's event if the process awaits this message. A type not declared in the definition
     * is a caller bug, not a race outcome, so it throws.
     */
    private Optional<ProcessSpec.MessageEvent<?>> expectedMessageEvent(ProcessInstance process, MessageType<?> type) {
        if (!process.getProcessSpec().declaresMessage(type)) {
            throw new IllegalArgumentException("Message " + type.name() + " with payload " + type.clazz().getName()
                    + " is not declared in process " + process.getProcessSpec().getType().name());
        }
        return process.expectedMessageEvent(type);
    }

    private static MessageNotExpectedException messageNotExpected(ProcessInstance process, String messageName) {
        return new MessageNotExpectedException(process.getId(), messageName, process.getStatus(), process.getState(),
                process.getProcessSpec().getApplicableMessageTypeNames(process.getState()));
    }

    /**
     * Runs the action in a transaction and throws the rejection it returned only after leaving it. A rejection is
     * an expected race outcome, not an error: thrown inside the action, it would mark the caller's transaction
     * rollback-only even if the caller catches it.
     */
    private void doInTransactionOrReject(Supplier<@Nullable ProcessEngineException> action) {
        var rejection = database.doInTransactionWithResult(action);
        if (rejection != null) throw rejection;
    }

    private ProcessInstance findProcessWithLock(UUID processId) {
        return processInstanceDao.findWithLockById(processId)
            .orElseThrow(() -> new ProcessNotFoundException(processId));
    }
}
