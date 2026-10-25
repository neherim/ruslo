package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.TimerType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Creates and starts a process. */
public class ProcessStartBuilder {
    private final ProcessType type;
    private final ProcessStarter processStarter;
    private final Map<String, Object> variables;
    private final Map<TimerType, Instant> timers;
    private String businessKey;
    private String correlationId;

    public ProcessStartBuilder(ProcessType type, ProcessStarter processStarter) {
        this.type = type;
        this.processStarter = processStarter;
        this.variables = new HashMap<>();
        this.timers = new HashMap<>();
    }

    /**
     * Stores the process in the caller's transaction or a new one and returns its id. Steps run on the dispatcher
     * after commit. Without a correlation id, the current one from MDC is used or a new one is generated.
     *
     * @throws IllegalArgumentException if no {@code at} of the process uses a {@link #timer timer}
     */
    public UUID start() {
        return processStarter.start(type, businessKey, correlationId, variables, timers);
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

    /**
     * Sets an initial process timer, as {@link io.github.neherim.ruslo.core.api.definition.ProcessContext#setTimer}
     * does in a step.
     *
     * @throws NullPointerException if {@code moment} is {@code null}
     */
    public ProcessStartBuilder timer(TimerType timer, Instant moment) {
        this.timers.put(Objects.requireNonNull(timer, "Timer type cannot be null"),
                Objects.requireNonNull(moment, "Timer moment cannot be null"));
        return this;
    }

    /** Engine callback that starts the process from the collected data. */
    @FunctionalInterface
    public interface ProcessStarter {

        UUID start(
                ProcessType type,
                @Nullable String businessKey,
                @Nullable String correlationId,
                Map<String, Object> variables,
                Map<TimerType, Instant> timers
        );
    }
}
