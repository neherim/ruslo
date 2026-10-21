package io.github.neherim.ruslo.core.api.test;

import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.core.runtime.process.ProcessVariables;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@link io.github.neherim.ruslo.core.api.definition.ProcessContext} for unit tests of handlers, without an engine
 * or database. Variables are stored as JSON like in the engine, so a test catches non-serializable values and
 * object changes made without {@code setVariable}.
 */
public final class TestProcessContext implements ProcessContext {
    private final UUID processId;
    private final String correlationId;
    private final ProcessTypeId processType;
    private final ProcessStateId state;
    private final int attempt;
    private final String businessKey;
    private final Clock clock;
    private final ProcessVariables variables;

    private TestProcessContext(Builder builder) {
        this.processId = builder.processId;
        this.correlationId = builder.correlationId;
        this.processType = builder.processType;
        this.state = builder.state;
        this.attempt = builder.attempt;
        this.businessKey = builder.businessKey;
        this.clock = builder.clock;
        this.variables = ProcessVariables.fromMap(builder.jsonMapper, builder.variables);
    }

    /** Returns a context with {@link #builder()} defaults. */
    public static TestProcessContext create() {
        return builder().build();
    }

    /**
     * Returns a builder. Defaults: random process and correlation ids, type and state {@code "test"}, attempt
     * {@code 0}, no business key or variables, {@link #now()} from the system clock.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Returns the immutable set of variable names, e.g. to check that a step wrote nothing extra. */
    public Set<String> variableNames() {
        return variables.asMap().keySet();
    }

    @Override
    public UUID processId() {
        return processId;
    }

    @Override
    public String correlationId() {
        return correlationId;
    }

    @Override
    public ProcessTypeId processType() {
        return processType;
    }

    @Override
    public ProcessStateId state() {
        return state;
    }

    @Override
    public <T> T getVariable(String name, Class<T> clazz) {
        return variables.getVariable(name, clazz);
    }

    @Override
    public <T> T getVariable(VariableType<T> type) {
        return getVariable(type.name(), type.clazz());
    }

    @Override
    public void setVariable(String name, Object value) {
        variables.setVariable(name, value);
    }

    @Override
    public <T> void setVariable(VariableType<T> type, T value) {
        setVariable(type.name(), value);
    }

    @Override
    public boolean removeVariable(String name) {
        return variables.removeVariable(name);
    }

    @Override
    public <T> boolean removeVariable(VariableType<T> type) {
        return removeVariable(type.name());
    }

    @Override
    public int attempt() {
        return attempt;
    }

    @Override
    public Optional<String> businessKey() {
        return Optional.ofNullable(businessKey);
    }

    @Override
    public Instant now() {
        return clock.instant();
    }

    public static final class Builder {
        private UUID processId = UUID.randomUUID();
        private String correlationId = UUID.randomUUID().toString();
        private ProcessTypeId processType = ProcessType.of("test");
        private ProcessStateId state = ProcessState.of("test");
        private int attempt;
        private String businessKey;
        private Clock clock = Clock.systemUTC();
        private ObjectMapper jsonMapper = JsonUtils.DEFAULT_OBJECT_MAPPER;
        private final Map<String, Object> variables = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder processId(UUID processId) {
            this.processId = Objects.requireNonNull(processId, "Process id must not be null");
            return this;
        }

        public Builder correlationId(String correlationId) {
            this.correlationId = Objects.requireNonNull(correlationId, "Correlation id must not be null");
            return this;
        }

        public Builder processType(ProcessType processType) {
            this.processType = Objects.requireNonNull(processType, "Process type must not be null").id();
            return this;
        }

        public Builder state(ProcessState state) {
            this.state = Objects.requireNonNull(state, "Process state must not be null").id();
            return this;
        }

        /** Sets {@link #attempt()}: {@code 0} for the first execution, then the retry number. */
        public Builder attempt(int attempt) {
            if (attempt < 0) {
                throw new IllegalArgumentException("Attempt must not be negative");
            }
            this.attempt = attempt;
            return this;
        }

        public Builder businessKey(String businessKey) {
            this.businessKey = businessKey;
            return this;
        }

        /** Fixes {@link #now()} at {@code now}. */
        public Builder now(Instant now) {
            this.clock = Clock.fixed(Objects.requireNonNull(now, "Now must not be null"), ZoneOffset.UTC);
            return this;
        }

        /**
         * Sets the variable mapper. Use the one passed to {@code ProcessEngineBuilder.withJsonMapper} so variables
         * serialize as in the application.
         */
        public Builder jsonMapper(ObjectMapper jsonMapper) {
            this.jsonMapper = Objects.requireNonNull(jsonMapper, "ObjectMapper must not be null");
            return this;
        }

        /** Adds an initial variable, serialized to JSON by {@link #build()}. */
        public Builder variable(String name, Object value) {
            this.variables.put(Objects.requireNonNull(name, "Variable name must not be null"), value);
            return this;
        }

        /** Adds an initial variable, serialized to JSON by {@link #build()}. */
        public <T> Builder variable(VariableType<T> type, T value) {
            return variable(type.name(), value);
        }

        public TestProcessContext build() {
            return new TestProcessContext(this);
        }
    }
}
