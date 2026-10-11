package io.github.neherim.ruslo.examples.cookbook.webhook;

import java.util.HashSet;
import java.util.Set;

/**
 * Ids of handled external service events. A process variable is stored as JSON, so the set is wrapped in a record:
 * generic classes are not supported as variable types.
 */
public record ProcessedEvents(Set<String> eventIds) {
    public static final ProcessedEvents NONE = new ProcessedEvents(Set.of());

    public ProcessedEvents {
        eventIds = Set.copyOf(eventIds);
    }

    /** Returns whether the event has been handled. */
    public boolean contains(String eventId) {
        return eventIds.contains(eventId);
    }

    /** Returns a copy with the event added. */
    public ProcessedEvents with(String eventId) {
        var ids = new HashSet<>(eventIds);
        ids.add(eventId);
        return new ProcessedEvents(ids);
    }
}
