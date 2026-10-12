package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.definition.ErrorResult;
import io.github.neherim.ruslo.core.api.definition.ProcessError;
import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseLostException;
import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.DefaultProcessContext;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** Handles system and handler errors raised while executing a process step. */
public class StepErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(StepErrorHandler.class);
    private static final Duration SYSTEM_RETRY_DELAY = Duration.ofSeconds(60);
    private static final int MAX_LAST_ERROR_LENGTH = 1024;
    private final Clock clock;
    private final Database database;
    private final ProcessInstanceDao processInstanceDao;
    private final ProcessMdc processMdc;
    private final ProcessEngineMetrics metrics;

    public StepErrorHandler(
            Clock clock,
            Database database,
            ProcessInstanceDao processInstanceDao,
            ProcessMdc processMdc,
            ProcessEngineMetrics metrics
    ) {
        this.clock = clock;
        this.database = database;
        this.processInstanceDao = processInstanceDao;
        this.processMdc = processMdc;
        this.metrics = metrics;
    }

    /**
     * Postpones the process by {@link #SYSTEM_RETRY_DELAY} after a system error and releases its lease.
     * The process is not loaded, so the error is handled even for a process that cannot be read.
     * A lost lease is a normal concurrent outcome and leaves the process state unchanged.
     *
     * @throws LeaseLostException if the lease is lost
     */
    public void handleSystemError(Throwable ex, ProcessLease lease) {
        log.error("System error in process {}: {}", lease.processId(), ex.getMessage(), ex);
        metrics.recordSystemError();
        processInstanceDao.postponeClaimed(lease, clock.instant().plus(SYSTEM_RETRY_DELAY), formatLastError(ex));
    }

    /**
     * Handles a step or event handler failure, only while the lease of the original worker task holds.
     *
     * @throws LeaseLostException if the lease is lost
     */
    public void handleHandlerError(Throwable cause, ProcessLease lease) {
        database.doInTransaction(() -> {
            var claimed = processInstanceDao.findClaimedWithLock(lease)
                .orElseThrow(() -> new LeaseLostException(lease.processId()));
            var process = claimed.process();
            try (var ignored = processMdc.open(process)) {
                log.error("Handle process step execution error", cause);
                applyErrorPolicy(process, cause);
                processInstanceDao.updateAndReleaseLease(claimed);
            }
        });
    }

    /**
     * Applies the same error policy after an administrative execution of {@code state} was rolled back.
     *
     * @return {@code true} if the process is ready to continue immediately, e.g. {@code fallback} or a zero-delay
     *         retry leads to a step
     */
    public boolean handleAdministrativeError(Throwable cause, UUID processId, ProcessStateId state) {
        log.error("Administrative step execution failed for process {}", processId, cause);
        try {
            return database.doInTransactionWithResult(() -> {
                var process = processInstanceDao.findWithLockById(processId).orElse(null);
                if (process == null) return false;
                try (var ignored = processMdc.open(process)) {
                    if (process.getStatus() != ProcessStatus.ACTIVE || !process.getState().is(state)) {
                        log.debug("Skip administrative error because process state changed for {}", processId);
                        return false;
                    }
                    applyErrorPolicy(process, cause);
                    processInstanceDao.update(process);
                    return process.isTimeToExecute();
                }
            });
        } catch (RuntimeException ex) {
            log.error("Administrative error handling failed for process {}", processId, ex);
            return false;
        }
    }

    /**
     * Applies the process error policy. If the user {@code ProcessErrorHandler} fails or returns {@code null},
     * the original event is retried after {@link #SYSTEM_RETRY_DELAY}. Entering {@code FAILED} is counted in metrics
     * after the caller's transaction commits.
     */
    private void applyErrorPolicy(ProcessInstance process, Throwable cause) {
        var error = formatLastError(cause);
        ErrorResult result;
        try {
            result = Objects.requireNonNull(process.getProcessErrorHandler()
                .handle(new DefaultProcessContext(process), new ProcessError(cause, process.getAttempt())),
                    "Process error handler returned null");
        } catch (RuntimeException ex) {
            log.error("Process error handler failed", ex);
            result = ErrorResult.retryAfter(SYSTEM_RETRY_DELAY);
        }
        switch (result) {
            case ErrorResult.RetryAfter retry -> process.scheduleRetryAfter(retry.delay(), error);
            case ErrorResult.Fail ignored -> markFailed(process, error);
            case ErrorResult.Fallback ignored -> {
                var fallback = process.getProcessSpec().getFallbackState().orElse(null);
                if (fallback != null && !process.getState().is(fallback)) process.enterState(fallback);
                else
                    markFailed(process, error);
            }
        }
    }

    private void markFailed(ProcessInstance process, String error) {
        process.markFailed(error);
        database.doAfterCommit(() -> metrics.recordProcessFailed(process.getType()));
    }

    private String formatLastError(Throwable ex) {
        var className = ex.getClass().getSimpleName();
        var message = ex.getMessage();
        var value = message == null || message.isBlank()
                ? className
                : className + ": " + message;
        if (value.length() <= MAX_LAST_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_LAST_ERROR_LENGTH);
    }
}
