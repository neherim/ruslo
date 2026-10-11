package io.github.neherim.ruslo.examples.quickstart;

import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.examples.quickstart.steps.CreateOrderStep;
import io.github.neherim.ruslo.examples.quickstart.steps.DeliverOrderStep;
import io.github.neherim.ruslo.examples.quickstart.steps.WaitingForPaymentHandlers;
import io.github.neherim.ruslo.spring.definition.SpringProcessDefinition;
import org.springframework.stereotype.Component;

import static io.github.neherim.ruslo.examples.quickstart.Constants.ORDER_CANCELLED;
import static io.github.neherim.ruslo.examples.quickstart.Constants.PAYMENT_CONFIRMED;
import static io.github.neherim.ruslo.examples.quickstart.OrderState.*;

@Component
public class OrderProcessDefinition extends SpringProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("ORDER");

    /** Describes the states of a basic order process. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .initialState(CREATED)
            .step(CREATED, bean(CreateOrderStep.class), WAITING_FOR_PAYMENT)
            .await(WAITING_FOR_PAYMENT, await -> await
                .message(PAYMENT_CONFIRMED, bean(WaitingForPaymentHandlers.class)::onPaymentConfirmed, DELIVERING)
                .message(ORDER_CANCELLED, bean(WaitingForPaymentHandlers.class)::onCancelled, CANCELLED))
            .step(DELIVERING, bean(DeliverOrderStep.class), COMPLETED)
            .terminal(COMPLETED)
            .terminal(CANCELLED)
            .build();
    }
}
