package io.github.neherim.ruslo.admin.dto;

import java.util.List;

/** Process definition metadata. */
public record DefinitionResponse(String type, String description, String initialState,
                                 List<StateResponse> states) {
    /**
     * Public state properties; {@code description} is {@code null} if the state has no description other
     * than its name.
     */
    public record StateResponse(String name, String description, String kind, String workerId,
                                List<String> acceptedMessageTypes, String timerDelay) {
    }
}
