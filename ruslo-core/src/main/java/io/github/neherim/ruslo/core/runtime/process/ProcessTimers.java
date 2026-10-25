package io.github.neherim.ruslo.core.runtime.process;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Process timers stored as one JSON object: timer name to its moment, in the {@link Instant} format of the variables.
 * Moments are truncated to microseconds, the precision of {@code next_run_at}: a finer moment would make the process
 * runnable slightly before its timer is due.
 */
public final class ProcessTimers {
    private static final TypeReference<TreeMap<String, Instant>> TIMERS_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final Map<String, Instant> timers;

    private ProcessTimers(ObjectMapper objectMapper, Map<String, Instant> timers) {
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.timers = timers;
    }

    public static ProcessTimers empty(ObjectMapper objectMapper) {
        return new ProcessTimers(objectMapper, new TreeMap<>());
    }

    public static ProcessTimers fromString(ObjectMapper objectMapper, String json) {
        if (json == null || json.isBlank()) {
            return empty(objectMapper);
        }
        return new ProcessTimers(objectMapper, objectMapper.readValue(json, TIMERS_TYPE));
    }

    /** Serializes the timers; an empty set is stored as {@code null} (the {@code timers} column is nullable). */
    public String serializeToString() {
        if (timers.isEmpty()) {
            return null;
        }
        return objectMapper.writeValueAsString(timers);
    }

    /** Sets the timer to {@code moment} truncated to microseconds. */
    public void set(String name, Instant moment) {
        Objects.requireNonNull(moment, "Timer moment cannot be null");
        timers.put(name, moment.truncatedTo(ChronoUnit.MICROS));
    }

    /**
     * Cancels the timer.
     *
     * @return {@code true} if the timer was set
     */
    public boolean cancel(String name) {
        return timers.remove(name) != null;
    }

    public Optional<Instant> get(String name) {
        return Optional.ofNullable(timers.get(name));
    }

    /** Returns an immutable map of timer name to moment. */
    public Map<String, Instant> asMap() {
        return Map.copyOf(timers);
    }
}
