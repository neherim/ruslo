package io.github.neherim.ruslo.examples.cookbook.approval;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/** Stub for the org chart. A real application uses its employee directory here. */
@Component
public class ApproverDirectory {
    private static final Map<String, String> MANAGERS = Map.of(
            "alice", "bob",
            "bob", "carol");

    /** Returns the employee's manager; the top manager has none. */
    public Optional<String> managerOf(String employee) {
        return Optional.ofNullable(MANAGERS.get(employee));
    }
}
