package io.github.neherim.ruslo.examples.cookbook.subscription;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import org.springframework.stereotype.Component;

/** Subscription event handlers. */
@Component
public class BillingHandlers {
    private final BillingGateway billingGateway;

    public BillingHandlers(BillingGateway billingGateway) {
        this.billingGateway = billingGateway;
    }

    /** Charges the next period to the current card. */
    public Billing charge(ProcessContext context) {
        return chargeNextPeriod(context);
    }

    /** Stores the new card and charges the debt to it at once. */
    public Billing onCardUpdated(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(SubscriptionDefinition.CARD_TOKEN, message.payload());
        return chargeNextPeriod(context);
    }

    /** Stores the cancellation reason. */
    public void onCancel(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(SubscriptionDefinition.CANCELLATION_REASON, message.payload());
    }

    /**
     * Charges the period after the last paid one. The idempotency key is the same for retries of one charge and
     * changes with the period or the card.
     */
    private Billing chargeNextPeriod(ProcessContext context) {
        var period = context.getVariable(SubscriptionDefinition.PAID_PERIODS) + 1;
        var cardToken = context.getVariable(SubscriptionDefinition.CARD_TOKEN);
        var idempotencyKey = context.businessKey().orElseThrow() + "/" + period + "/" + cardToken;
        if (!billingGateway.charge(idempotencyKey, cardToken, context.getVariable(SubscriptionDefinition.PRICE))) {
            return Billing.DECLINED;
        }
        context.setVariable(SubscriptionDefinition.PAID_PERIODS, period);
        return Billing.PAID;
    }

    /** Bank's answer to the charge. */
    public enum Billing {
        PAID,
        DECLINED
    }
}
