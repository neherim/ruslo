package io.github.neherim.ruslo.examples.quickstart;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.examples.quickstart.dto.PaymentConfirmed;

public class Constants {
    public static final VariableType<String> CUSTOMER_ID = VariableType.of("customerId");
    public static final VariableType<String> CANCELLATION_REASON = VariableType.of("cancellationReason");
    public static final MessageType<PaymentConfirmed> PAYMENT_CONFIRMED = MessageType.of("paymentConfirmed");
    public static final MessageType<String> ORDER_CANCELLED = MessageType.of("orderCancelled");
}
