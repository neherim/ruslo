package io.github.neherim.ruslo.admin.dev.process;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import org.springframework.stereotype.Component;

/**
 * Support ticket process for manual checks of the UI.
 */
@Component
public class SupportTicketProcessDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("SUPPORT_TICKET");

    public enum State implements ProcessState {
        OPEN("New ticket"),
        WAITING_FOR_AGENT("Waiting for agent"),
        RESOLVED("Ticket resolved");

        private final String description;

        State(String description) {
            this.description = description;
        }

        @Override
        public String description() {
            return description;
        }
    }

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .description("Sample support ticket processing")
                .initialState(State.OPEN)
                .step(State.OPEN, context -> { }, State.WAITING_FOR_AGENT)
                .await(State.WAITING_FOR_AGENT, await -> await.message(
                    MessageType.of("resolveTicket", String.class), (context, message) -> { },
                    State.RESOLVED))
                .terminal(State.RESOLVED)
                .build();
    }
}
