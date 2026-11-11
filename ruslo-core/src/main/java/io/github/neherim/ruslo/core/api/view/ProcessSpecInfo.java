package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only view of a registered process definition. */
public record ProcessSpecInfo(
        ProcessType type,
        Optional<String> description,
        ProcessState initialState,
        Set<StateInfo> states
) {
    /**
     * State of the definition.
     *
     * @param description from {@link io.github.neherim.ruslo.core.api.definition.ProcessState#description()}
     * @param atTimer     name of the process timer the {@code at} event waits for
     */
    public record StateInfo(
            ProcessState state,
            String description,
            StateKind kind,
            Optional<WorkerId> workerId,
            Set<String> acceptedMessageTypes,
            Optional<Duration> everyInterval,
            Optional<Duration> afterDelay,
            Optional<String> atTimer,
            List<TransitionInfo> transitions
    ) {
    }

    /**
     * Declared transition.
     *
     * @param messageType present only for {@link TransitionTrigger#MESSAGE}
     * @param outcome     present only for branching handlers
     * @param label       optional diagram label replacing the outcome name
     * @param target      equals the source state for a transition to the same state
     */
    public record TransitionInfo(
            TransitionTrigger trigger,
            Optional<String> messageType,
            Optional<String> outcome,
            ProcessState target,
            Optional<String> label
    ) {
    }

    public enum StateKind {
        STEP,
        AWAIT,
        TERMINAL
    }

    public enum TransitionTrigger {
        STEP,
        MESSAGE,
        EVERY,
        AFTER,
        AT
    }
}
