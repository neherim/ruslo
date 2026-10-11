package io.github.neherim.ruslo.examples.quickstart.steps;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.examples.quickstart.Constants;
import io.github.neherim.ruslo.examples.quickstart.dto.PaymentConfirmed;
import org.springframework.stereotype.Component;

/** Event handlers of the waiting-for-payment state. */
@Component
public class WaitingForPaymentHandlers {
    /**
     * Accepts the payment confirmation. Nothing else to do: the definition declares the transition to delivery,
     * so the handler may be empty.
     */
    public void onPaymentConfirmed(ProcessContext context, IncomingMessage<PaymentConfirmed> message) {
    }

    /** Stores the cancellation reason. */
    public void onCancelled(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(Constants.CANCELLATION_REASON, message.payload());
    }
}
