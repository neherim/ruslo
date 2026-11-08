package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.TimerType;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.utils.UUIDGenerator;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Creates new process instances and restores them from storage. */
public class ProcessInstanceFactory {
    private final Clock clock;
    private final ProcessDefinitionRegistry processDefinitionRegistry;

    public ProcessInstanceFactory(
            Clock clock,
            ProcessDefinitionRegistry processDefinitionRegistry
    ) {
        this.clock = clock;
        this.processDefinitionRegistry = processDefinitionRegistry;
    }

    ProcessInstance loadFromStorage(
            UUID id,
            String businessKey,
            String processTypeName,
            String processState,
            String correlationId,
            ProcessStatus status,
            String variablesStr,
            String timersStr,
            String messagesStr,
            int attempt,
            String lastError,
            Instant nextRunAt,
            Instant everyDueAt,
            Instant afterDueAt
    ) {

        var processType = ProcessType.of(processTypeName);
        var state = ProcessState.of(processState);
        var spec = processDefinitionRegistry.getProcessSpec(processType);

        var variables = ProcessVariables.fromString(variablesStr);
        var timers = ProcessTimers.fromString(timersStr);
        var messages = ProcessMessages.fromString(messagesStr);

        return ProcessInstance.loadFromStorage(id, businessKey, spec, state, status, variables, timers, messages,
                correlationId, attempt, lastError, nextRunAt, everyDueAt, afterDueAt, clock);
    }

    /**
     * Creates a process in the initial state.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses one of {@code timers}
     */
    public ProcessInstance newInstance(
            ProcessType type,
            Instant startDate,
            @Nullable String businessKey,
            String correlationId,
            Map<String, Object> variables,
            Map<TimerType, Instant> timers
    ) {
        var id = UUIDGenerator.generate();
        var spec = processDefinitionRegistry.getProcessSpec(type);
        var processVariables = ProcessVariables.fromMap(variables);
        var processTimers = ProcessTimers.empty();
        timers.forEach((timer, moment) -> {
            spec.checkTimerDeclared(timer);
            processTimers.set(timer.name(), moment);
        });

        return ProcessInstance.createNew(id, businessKey, spec,
                processVariables, processTimers, ProcessMessages.empty(),
                correlationId, startDate, clock);
    }
}
