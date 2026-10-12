package io.github.neherim.ruslo.admin.dev.process;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Order process for manual checks of the UI.
 */
@Component
public class OrderProcessDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER");

    public enum State implements ProcessState {
        CHECK_ORDER("Order check"),
        WAITING_FOR_PAYMENT("Waiting for payment"),
        MANUAL_REVIEW("Manual review"),
        COMPLETED("Order completed"),
        REJECTED("Order rejected");

        private final String description;

        State(String description) {
            this.description = description;
        }

        @Override
        public String description() {
            return description;
        }
    }

    /** Result of the automatic order check. */
    public enum CheckResult {
        OK,
        SUSPICIOUS,
        INVALID
    }

    /** Result of the periodic payment check. */
    public enum PaymentCheck {
        PAID,
        NOT_YET
    }

    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .description("Sample order processing")
            .initialState(State.CHECK_ORDER)
            .step(State.CHECK_ORDER, context -> CheckResult.OK, routes -> routes
                .on(CheckResult.OK, State.WAITING_FOR_PAYMENT)
                .on(CheckResult.SUSPICIOUS, State.MANUAL_REVIEW)
                .on(CheckResult.INVALID, State.REJECTED))
            .await(State.WAITING_FOR_PAYMENT, await -> await
                .every(Duration.ofSeconds(30), context -> PaymentCheck.NOT_YET, routes -> routes
                    .on(PaymentCheck.PAID, State.COMPLETED)
                    .on(PaymentCheck.NOT_YET, State.WAITING_FOR_PAYMENT))
                .timeout(Duration.ofHours(1), State.REJECTED))
            .await(State.MANUAL_REVIEW, await -> await
                .message(MessageType.of("approveReview", String.class), (context, message) -> {},
                        State.WAITING_FOR_PAYMENT)
                .message(MessageType.of("rejectReview", String.class), (context, message) -> {},
                        State.REJECTED))
            .terminal(State.COMPLETED)
            .terminal(State.REJECTED)
            .build();
    }
}
