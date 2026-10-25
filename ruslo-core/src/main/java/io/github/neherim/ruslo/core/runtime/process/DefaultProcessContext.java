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
    public ProcessTypeId processType() {
        return processInstance.getType();
    }

    @Override
    public ProcessStateId state() {
        return processInstance.getState();
    }

    @Override
    public <T> T getVariable(String name, Class<T> clazz) {
        return processInstance.getVariable(name, clazz);
    }

    @Override
    public <T> T getVariable(VariableType<T> type) {
        return getVariable(type.name(), type.clazz());
    }

    @Override
    public void setVariable(String name, Object value) {
        processInstance.setVariable(name, value);
    }

    @Override
    public <T> void setVariable(VariableType<T> type, T value) {
        setVariable(type.name(), value);
    }

    @Override
    public boolean removeVariable(String name) {
        return processInstance.removeVariable(name);
    }

    @Override
    public <T> boolean removeVariable(VariableType<T> type) {
        return removeVariable(type.name());
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
    public Optional<Instant> getTimer(TimerType timer) {
        processInstance.getProcessSpec().checkTimerDeclared(timer);
        return processInstance.getTimer(timer);
    }

    @Override
    public int attempt() {
        return processInstance.getAttempt();
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
