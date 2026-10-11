package io.github.neherim.ruslo.admin.dto;

import java.util.List;

/** State and transition graph of a process definition for the diagram. */
public record ProcessGraphResponse(String initialState, List<Node> nodes, List<Edge> edges) {
    /**
     * Process state: {@code id} is the state name, {@code label} its description, {@code worker} the worker of
     * a step or await; {@code null} for a terminal state.
     */
    public record Node(String id, String label, String kind, String worker) {
    }

    /** Declared transition; {@code label} may be {@code null} for a linear step. */
    public record Edge(String source, String target, String trigger, String label) {
    }
}
