package io.github.neherim.ruslo.core.runtime.logging;

import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import org.slf4j.MDC;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Puts process values into the MDC and restores the previous values afterwards. */
public class ProcessMdc {
    private final MdcKeys keys;

    public ProcessMdc(MdcKeys keys) {
        this.keys = Objects.requireNonNull(keys);
    }

    /**
     * Puts the process values into the MDC.
     *
     * @return a scope to close after logging or step execution; closing restores the previous MDC values
     */
    public Scope open(ProcessInstance process) {
        var values = new LinkedHashMap<String, String>();
        values.put(keys.correlationId(), process.getCorrelationId());
        values.put(keys.processId(), process.getId().toString());
        values.put(keys.processType(), process.getType().name());
        return new Scope(values);
    }

    /** Returns the correlation id from the MDC, or a new random UUID if there is none. */
    public String getOrCreateCorrelationId() {
        var correlationId = MDC.get(keys.correlationId());
        if (correlationId == null || correlationId.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return correlationId;
    }

    public static final class Scope implements AutoCloseable {
        private final Map<String, String> previousValues = new LinkedHashMap<>();

        private Scope(Map<String, String> values) {
            values.forEach((key, value) -> {
                previousValues.put(key, MDC.get(key));
                MDC.put(key, value);
            });
        }

        @Override
        public void close() {
            previousValues.forEach((key, value) -> {
                if (value == null) {
                    MDC.remove(key);
                } else {
                    MDC.put(key, value);
                }
            });
        }
    }
}
