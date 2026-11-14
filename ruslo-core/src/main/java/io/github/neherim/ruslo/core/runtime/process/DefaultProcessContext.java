package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.*;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class DefaultProcessContext implements ProcessContext {
    private final ProcessInstance processInstance;

    public DefaultProcessContext(ProcessInstance processInstance) {
        this.processInstance = processInstance;
    }

    @Override
    public UUID processId() {
        return processInstance.getId();
    }

    @Override
    public String correlationId() {
        return processInstance.getCorrelationId();
    }

    @Override
    public ProcessType processType() {
        return processInstance.getDeclaredType();
    }

    @Override
    public ProcessState state() {
        return processInstance.getDeclaredState();
    }

    @Override
    public <T> Optional<T> findVariable(VariableType<T> type) {
        return processInstance.findVariable(type.name(), type.clazz());
    }

    @Override
    public <T> void setVariable(VariableType<T> type, T value) {
        processInstance.setVariable(type.name(), value);
    }

    @Override
    public <T> boolean removeVariable(VariableType<T> type) {
        return processInstance.removeVariable(type.name());
    }

    @Override
    public void setTimer(TimerType timer, Instant moment) {
        processInstance.getProcessSpec().checkTimerDeclared(timer);
        processInstance.setTimer(timer, moment);
    }

    @Override
    public boolean cancelTimer(TimerType timer) {
        processInstance.getProcessSpec().checkTimerDeclared(timer);
        return processInstance.cancelTimer(timer);
    }

    @Override
    public Optional<Instant> findTimer(TimerType timer) {
        processInstance.getProcessSpec().checkTimerDeclared(timer);
        return processInstance.getTimer(timer);
    }

    @Override
    public int attempt() {
        return processInstance.getFailedAttempts();
    }

    @Override
    public Optional<String> businessKey() {
        return processInstance.getBusinessKey();
    }

    @Override
    public Instant now() {
        return processInstance.now();
    }
}
