package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Creates and starts a process. */
public class ProcessStartBuilder {
    private final ProcessType type;
    private final ProcessStarter processStarter;
    private final Map<String, Object> variables;
    private String businessKey;
    private String correlationId;

    public ProcessStartBuilder(ProcessType type, ProcessStarter processStarter) {
        this.type = type;
        this.processStarter = processStarter;
        this.variables = new HashMap<>();
    }

    /**
     * Stores the process in the caller's transaction or a new one and returns its id. Steps run on the dispatcher
     * after commit. Without a correlation id, the current one from MDC is used or a new one is generated.
     */
    public UUID start() {
        return processStarter.start(type, businessKey, correlationId, variables);
    }

    public ProcessStartBuilder businessKey(String businessKey) {
        this.businessKey = businessKey;
        return this;
    }

    /** Sets the correlation id used in logs and search. */
    public ProcessStartBuilder correlationId(String correlationId) {
        this.correlationId = correlationId;
        return this;
    }

    /** Sets an initial variable; the value is serialized to JSON. */
    public ProcessStartBuilder variable(String name, Object value) {
        this.variables.put(name, value);
        return this;
    }

    /** Sets an initial variable; the value is serialized to JSON. */
    public <T> ProcessStartBuilder variable(VariableType<T> type, T value) {
        this.variables.put(type.name(), value);
        return this;
    }

    /** Engine callback that starts the process from the collected data. */
    @FunctionalInterface
    public interface ProcessStarter {

        UUID start(
                ProcessType type,
                @Nullable String businessKey,
                @Nullable String correlationId,
                Map<String, Object> variables
        );
    }
}
