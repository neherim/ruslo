package io.github.neherim.ruslo.core.runtime.process;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectReader;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import static io.github.neherim.ruslo.core.utils.DateTimeUtils.ceilToMicros;
import static io.github.neherim.ruslo.core.utils.JsonUtils.DEFAULT_OBJECT_MAPPER;

/**
 * Process timers stored as one JSON object: timer name to its moment, in the {@link Instant} format of the variables.
 * Moments are rounded up to microseconds, the precision of {@code next_run_at}: a finer moment would make the process
 * runnable slightly before its timer is due.
 */
public final class ProcessTimers {
    private static final ObjectReader JSON_READER = DEFAULT_OBJECT_MAPPER.readerFor(
            new TypeReference<TreeMap<String, Instant>>() {
            });

    private final Map<String, Instant> timers;

    private ProcessTimers(Map<String, Instant> timers) {
        this.timers = timers;
    }

    /** Creates an empty set of timers. */
    public static ProcessTimers empty() {
        return new ProcessTimers(new TreeMap<>());
    }

    /** Reads timers from a JSON object. */
    public static ProcessTimers fromString(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        return new ProcessTimers(JSON_READER.readValue(json));
    }

    /** Serializes the timers; an empty set is stored as {@code null} (the {@code timers} column is nullable). */
    public String serializeToString() {
        if (timers.isEmpty()) {
            return null;
        }
        return DEFAULT_OBJECT_MAPPER.writeValueAsString(timers);
    }

    /** Sets the timer to {@code moment} rounded up to microseconds. */
    public void set(String name, Instant moment) {
        Objects.requireNonNull(moment, "Timer moment cannot be null");
        timers.put(name, ceilToMicros(moment));
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
