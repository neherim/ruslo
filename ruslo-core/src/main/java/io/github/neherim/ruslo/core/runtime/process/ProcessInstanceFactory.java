package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.utils.UUIDGenerator;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Creates new process instances and restores them from storage. */
public class ProcessInstanceFactory {
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessDefinitionRegistry processDefinitionRegistry;

    public ProcessInstanceFactory(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessDefinitionRegistry processDefinitionRegistry
    ) {
        this.clock = clock;
        this.objectMapper = objectMapper;
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
            String messagesStr,
            int attempt,
            String lastError,
            Instant scheduledAt,
            Instant availableAt,
            Instant deadlineAt
    ) {

        var processType = ProcessType.of(processTypeName);
        var state = ProcessState.of(processState);
        var spec = processDefinitionRegistry.getProcessSpec(processType);

        var variables = ProcessVariables.fromString(objectMapper, variablesStr);
        var messages = ProcessMessages.fromString(objectMapper, messagesStr);

        return ProcessInstance.loadFromStorage(id, businessKey, spec, state, status, variables, messages,
                correlationId, attempt, lastError, scheduledAt, availableAt, deadlineAt, clock);
    }

    public ProcessInstance newInstance(
            ProcessType type,
            Instant startDate,
            @Nullable String businessKey,
            String correlationId,
            Map<String, Object> variables
    ) {
        var id = UUIDGenerator.generate();
        var spec = processDefinitionRegistry.getProcessSpec(type);
        var processVariables = ProcessVariables.fromMap(objectMapper, variables);

        return ProcessInstance.createNew(id, businessKey, spec,
                processVariables, ProcessMessages.empty(objectMapper),
                correlationId, startDate, clock);
    }
}
