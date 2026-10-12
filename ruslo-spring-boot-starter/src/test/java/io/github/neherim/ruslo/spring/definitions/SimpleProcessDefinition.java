package io.github.neherim.ruslo.spring.definitions;

import io.github.neherim.ruslo.core.api.definition.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SimpleProcessDefinition implements ProcessDefinition {
    public final static ProcessType TYPE = ProcessType.of("SIMPLE");
    private final static Logger log = LoggerFactory.getLogger(SimpleProcessDefinition.class);

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(State.INIT)
            .step(State.INIT, context -> {
                log.debug("INIT");
            }, State.FINISH)
            .terminal(State.FINISH)
            .build();
    }

    public enum State implements ProcessState {
        INIT,
        FINISH
    }
}
