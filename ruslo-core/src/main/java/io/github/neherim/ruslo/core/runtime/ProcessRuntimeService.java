package io.github.neherim.ruslo.core.runtime;

import io.github.neherim.ruslo.core.api.ProcessStartBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessEngineException;
import io.github.neherim.ruslo.core.api.exception.ProcessNotFoundException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchSignal;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceFactory;
import io.github.neherim.ruslo.core.runtime.process.ProcessMessages;
import io.github.neherim.ruslo.core.runtime.worker.HandlerException;
import io.github.neherim.ruslo.core.runtime.worker.StepErrorHandler;
import io.github.neherim.ruslo.core.runtime.worker.StepExecutor;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.EngineTime;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
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
    private final EngineTime engineTime;
    private final Database database;
    private final ProcessInstanceDao processInstanceDao;
    private final ProcessDispatchSignal dispatchSignal;
    private final ProcessInstanceFactory processInstanceFactory;
    private final ProcessEngineMetrics metrics;
    private final ProcessMdc processMdc;
    private final StepExecutor stepExecutor;
    private final StepErrorHandler stepErrorHandler;

    public ProcessRuntimeService(
            EngineTime engineTime,
            Database database,
            ProcessInstanceDao processInstanceDao,
            ProcessInstanceFactory processInstanceFactory,
            ProcessDispatchSignal dispatchSignal,
            ProcessEngineMetrics metrics,
            ProcessMdc processMdc,
            StepExecutor stepExecutor,
            StepErrorHandler stepErrorHandler
    ) {
        this.engineTime = engineTime;
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
     * Resumes a {@code FAILED} process at the target state, or at the current state when no target is given.
     * See {@link io.github.neherim.ruslo.core.api.ProcessEngine#resumeFailedProcess(UUID, ProcessState)}.
     *
     * @throws ProcessStatusException if the process is not {@code FAILED}; thrown after the transaction
     */
    public void resumeFailedProcess(UUID processId, @Nullable ProcessState targetState) {
        resumeFailedProcess(() -> getProcessWithLock(processId), targetState);
    }

    public void resumeFailedProcess(ProcessType processType, String businessKey, @Nullable ProcessState targetState) {
        resumeFailedProcess(() -> getProcessWithLock(processType, businessKey), targetState);
    }

    private void resumeFailedProcess(Supplier<ProcessInstance> loadWithLock, @Nullable ProcessState targetState) {
        doInTransactionOrReject(() -> {
            var process = loadWithLock.get();
            if (!process.resume(targetState)) {
                return new ProcessStatusException(process.getId(), process.getStatus(),
                        "only a FAILED process can be resumed");
            }
            saveAndWakeDispatcher(process);
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
            var process = getProcessWithLock(processId);
            process.changeState(processState);
            saveAndWakeDispatcher(process);
        });
    }

    /**
     * Queues the message for the current await, see
     * {@link io.github.neherim.ruslo.core.api.ProcessEngine#sendMessage(UUID, MessageType, Object)}.
     *
     * @throws MessageNotExpectedException if the current state does not await this type or the process is completed;
     *                                     thrown after the transaction
     * @throws IllegalArgumentException    if no await of the process declares this type
     */
    public <T> void sendMessage(UUID processId, MessageType<T> type, T message) {
        sendMessage(() -> getProcessWithLock(processId), type, message);
    }

    public <T> void sendMessage(ProcessType processType, String businessKey, MessageType<T> type, T message) {
        sendMessage(() -> getProcessWithLock(processType, businessKey), type, message);
    }

    private <T> void sendMessage(Supplier<ProcessInstance> loadWithLock, MessageType<T> type, T message) {
        doInTransactionOrReject(() -> {
            var process = loadWithLock.get();
            var spec = process.getProcessSpec();
            spec.checkMessageDeclared(type);
            var event = spec.getMessageEvent(type, process.getState());
            if (event.isEmpty()) return messageNotExpected(process, type.name());
            appendMessage(process, type, message);
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
            var process = getProcessWithLock(processId);
            var event = process.getProcessSpec()
                .getMessageEvent(name, process.getState())
                .orElseThrow(() -> messageNotExpected(process, name));
            appendJsonMessage(process, event.type(), jsonPayload);
        });
    }

    /**
     * Runs the message handler in the calling thread,
     * see {@link io.github.neherim.ruslo.core.api.ProcessEngine#sendMessageSync(UUID, MessageType, Object)}.
     * A handler exception propagates as is; engine rejections are thrown after the transaction.
     *
     * @throws MessageNotExpectedException if the current state does not await this type, the process is completed
     *                                     or {@code FAILED}
     * @throws IllegalArgumentException    if no await of the process declares this type
     */
    public <T> void sendMessageSync(UUID processId, MessageType<T> type, T message) {
        sendMessageSync(() -> getProcessWithLock(processId), type, message);
    }

    public <T> void sendMessageSync(ProcessType processType, String businessKey, MessageType<T> type, T message) {
        sendMessageSync(() -> getProcessWithLock(processType, businessKey), type, message);
    }

    private <T> void sendMessageSync(Supplier<ProcessInstance> loadWithLock, MessageType<T> type, T message) {
        try {
            doInTransactionOrReject(() -> {
                var process = loadWithLock.get();
                var spec = process.getProcessSpec();
                spec.checkMessageDeclared(type);
                var event = spec.getMessageEvent(type, process.getState());
                if (event.isEmpty()) return messageNotExpected(process, type.name());
                if (process.getStatus() == ProcessStatus.FAILED) {
                    return messageNotExpected(process, type.name(), "a FAILED process cannot handle a message "
                            + "synchronously; resume it or queue the message with sendMessage");
                }
                stepExecutor.executeMessageBypassingQueue(process, event.get(), message);
                saveAndWakeDispatcher(process);
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
     *         {@code giveUp} or a zero-delay retry leads to a step; {@code false} on completion, {@code FAILED},
     *         an await without a due event, a delayed retry or a step scheduled in the future
     * @throws ProcessStatusException if the process is completed or {@code FAILED}
     */
    public boolean forceExecuteOneStep(UUID processId) {
        var executedState = new AtomicReference<ProcessStateId>();
        try {
            return database.doInTransactionWithResult(() -> {
                var process = getProcessWithLock(processId);
                executedState.set(process.getState());
                if (process.getStatus() != ProcessStatus.ACTIVE) {
                    throw new ProcessStatusException(processId, process.getStatus(),
                            "only an ACTIVE process runs steps");
                }
                if (process.getCurrentStateSpec() instanceof ProcessSpec.AwaitState
                        && !process.isTimeToExecute()) {
                    return false;
                }
                stepExecutor.executeNext(process);
                saveAndWakeDispatcher(process);
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
            var process = getProcessWithLock(processId);
            process.setVariable(type, value);
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
            var process = getProcessWithLock(processId);
            var removed = process.removeVariable(name);
            if (removed) {
                processInstanceDao.update(process);
            }
            return removed;
        });
    }

    /**
     * Sets a process timer, see {@link io.github.neherim.ruslo.core.api.ProcessEngineAdmin#setProcessTimer}.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses the timer
     */
    public void setTimer(UUID processId, TimerType timer, Instant moment) {
        Objects.requireNonNull(moment, "Timer moment cannot be null");
        database.doInTransaction(() -> {
            var process = getProcessWithLock(processId);
            process.setTimer(timer, moment);
            saveAndWakeDispatcher(process);
        });
    }

    /**
     * Cancels a process timer without checking that the definition declares it.
     *
     * @return {@code true} if the timer was set
     */
    public boolean cancelTimer(UUID processId, TimerType timer) {
        return database.doInTransactionWithResult(() -> {
            var process = getProcessWithLock(processId);
            var cancelled = process.cancelTimer(timer);
            if (cancelled) {
                processInstanceDao.update(process);
            }
            return cancelled;
        });
    }

    /** Returns the number of {@code ACTIVE} and {@code FAILED} processes. */
    public long countIncompleteProcesses() {
        return processInstanceDao.countIncompleteProcesses();
    }

    private UUID startProcess(
            ProcessType type,
            @Nullable String businessKey,
            @Nullable String correlationId,
            Map<String, Object> variables,
            Map<TimerType, Instant> timers
    ) {
        var effectiveCorrelationId = correlationId != null && !correlationId.isBlank()
                ? correlationId
                : processMdc.getOrCreateCorrelationId();
        var process = processInstanceFactory.newInstance(type, engineTime.now(), businessKey,
                effectiveCorrelationId, variables, timers);

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

    private <T> void appendJsonMessage(ProcessInstance process, MessageType<T> type, @Nullable Object jsonPayload) {
        appendMessage(process, type, ProcessMessages.normalizePayload(jsonPayload, type.clazz()));
    }

    private <T> void appendMessage(ProcessInstance process, MessageType<T> type, @Nullable T payload) {
        process.appendMessage(type, payload);
        saveAndWakeDispatcher(process);
    }

    private void saveAndWakeDispatcher(ProcessInstance process) {
        processInstanceDao.update(process);
        database.doAfterCommit(dispatchSignal::wakeUp);
    }

    private static MessageNotExpectedException messageNotExpected(ProcessInstance process, String messageName) {
        var awaited = process.getProcessSpec().getApplicableMessageTypeNames(process.getState());
        String reason;
        if (process.isCompleted()) reason = "the process is completed";
        else if (awaited.isEmpty()) reason = "the state awaits no messages";
        else
            reason = "the state awaits only " + new TreeSet<>(awaited);
        return messageNotExpected(process, messageName, reason);
    }

    private static MessageNotExpectedException messageNotExpected(
            ProcessInstance process,
            String messageName,
            String reason
    ) {
        return new MessageNotExpectedException(process.getId(), messageName, process.getStatus(),
                process.getDeclaredState(),
                process.getProcessSpec().getApplicableMessageTypeNames(process.getState()), reason);
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

    private ProcessInstance getProcessWithLock(UUID processId) {
        return processInstanceDao.findWithLockById(processId)
            .orElseThrow(() -> new ProcessNotFoundException(processId));
    }

    private ProcessInstance getProcessWithLock(ProcessType processType, String businessKey) {
        Objects.requireNonNull(processType, "Process type cannot be null");
        Objects.requireNonNull(businessKey, "Business key cannot be null");
        return processInstanceDao.findIdByTypeAndBusinessKey(processType, businessKey)
            .flatMap(processInstanceDao::findWithLockById)
            .orElseThrow(() -> new ProcessNotFoundException(processType, businessKey));
    }
}
