package io.github.neherim.ruslo.core.api.view;

import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.definition.ProcessTypeId;
import io.github.neherim.ruslo.core.api.definition.WorkerId;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only view of a registered process definition. */
public record ProcessSpecInfo(
        ProcessTypeId type,
        Optional<String> description,
        ProcessStateId initialState,
        Set<StateInfo> states
) {
    /**
     * State of the definition.
     *
     * @param description from {@link io.github.neherim.ruslo.core.api.definition.ProcessState#description()}
     */
    public record StateInfo(
            ProcessStateId state,
            String description,
            StateKind kind,
            Optional<WorkerId> workerId,
            Set<String> acceptedMessageTypes,
            Optional<Duration> timeoutDelay,
            Optional<Duration> everyInterval,
            List<TransitionInfo> transitions
    ) {
    }

    /**
     * Declared transition.
     *
     * @param messageType present only for {@link TransitionTrigger#MESSAGE}
     * @param outcome     present only for branching handlers
     * @param target      equals the source await if the event keeps waiting
     */
    public record TransitionInfo(
            TransitionTrigger trigger,
            Optional<String> messageType,
            Optional<String> outcome,
            ProcessStateId target
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
        TIMEOUT
    }
}
