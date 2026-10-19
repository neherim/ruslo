package io.github.neherim.ruslo.examples.cookbook.webhook;

import java.util.ArrayList;
import java.util.List;

/**
 * Ids of callbacks the process has already handled. A record because a process variable cannot have a generic class
 * such as {@code List<String>}.
 */
public record HandledEvents(List<String> eventIds) {
    static final HandledEvents NONE = new HandledEvents(List.of());

    boolean contains(String eventId) {
        return eventIds.contains(eventId);
    }

    HandledEvents with(String eventId) {
        var ids = new ArrayList<>(eventIds);
        ids.add(eventId);
        return new HandledEvents(ids);
    }
}
