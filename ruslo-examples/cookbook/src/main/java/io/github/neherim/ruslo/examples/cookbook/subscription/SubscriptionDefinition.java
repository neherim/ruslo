package io.github.neherim.ruslo.examples.cookbook.subscription;

import io.github.neherim.ruslo.core.api.definition.IncomingMessage;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Recipe: a long-lived process, a monthly subscription charge until the customer cancels.
 * <p>
 * Task: the customer paid for the first period at sign-up. After that the card is charged every 30 days. If the bank
 * declines a charge, the subscription is suspended. The subscription can be cancelled at any time.
 * <p>
 * How it works:
 * <ul>
 *   <li>The process starts right in await {@link State#ACTIVE}: there is no step, the first period is already paid.
 *       The process business key is the subscription id.</li>
 *   <li>{@code every} in {@link State#ACTIVE} charges the fee. A successful charge targets the await itself: "wait
 *       for the next period". A bank decline leads to {@link State#SUSPENDED}.</li>
 *   <li>Message {@link #CANCEL} is declared in the same await, next to {@code every}: whichever event comes first is
 *       handled. A cancelled subscription is not charged again.</li>
 *   <li>Billing failures are retried through {@code onError}. A retried charge must not charge the period twice:
 *       pass the provider an idempotency key (recipe {@code externalcall}).</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>{@code every} is an interval, not a calendar: "every 30 days", not "on the 5th of every month". The next
 *       charge is scheduled from the time of handling, so retries shift the date. If the charge date matters by
 *       contract, keep the schedule in billing and let the process only react to it.</li>
 *   <li>Do not keep payment history in variables: all variables are stored as one JSON in the process row and are
 *       read on every event. The history lives in billing.</li>
 *   <li>Subscriptions live for years, while the definition changes with the application. Do not remove or rename
 *       states that hold live processes ({@code docs/concepts.md}, "Definition evolution").</li>
 * </ul>
 */
@Component
public class SubscriptionDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("SUBSCRIPTION");
    public static final Duration BILLING_PERIOD = Duration.ofDays(30);
    public static final VariableType<String> CARD_TOKEN = VariableType.of("cardToken", String.class);
    public static final VariableType<String> CANCELLATION_REASON = VariableType.of("cancellationReason", String.class);
    public static final MessageType<String> CANCEL = MessageType.of("cancel", String.class);

    /** Stands for a card the bank always declines. */
    public static final String DECLINED_CARD = "tok_declined";

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDefinition.class);

    /** Describes a subscription: periodic charge until cancellation or a bank decline. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .onError(ProcessErrorHandler.exponential(5, Duration.ofMinutes(1), Duration.ofHours(1)))
            .initialState(State.ACTIVE)
            .await(State.ACTIVE, await -> await
                .every(BILLING_PERIOD, SubscriptionDefinition::charge, routes -> routes
                    .on(Billing.PAID, State.ACTIVE)
                    .on(Billing.DECLINED, State.SUSPENDED))
                .message(CANCEL, SubscriptionDefinition::onCancel, State.CANCELLED))
            .terminal(State.CANCELLED)
            .terminal(State.SUSPENDED)
            .build();
    }

    /** Stands for a charge in billing. */
    private static Billing charge(ProcessContext context) {
        var cardToken = context.getVariable(CARD_TOKEN);
        var paid = !DECLINED_CARD.equals(cardToken);
        log.info("Subscription {} charged to {}: paid={}", context.businessKey().orElseThrow(), cardToken, paid);
        return paid ? Billing.PAID : Billing.DECLINED;
    }

    private static void onCancel(ProcessContext context, IncomingMessage<String> message) {
        context.setVariable(CANCELLATION_REASON, message.payload());
    }

    public enum State implements ProcessState {
        ACTIVE,
        CANCELLED,
        SUSPENDED
    }

    /** Bank's answer to the charge. */
    public enum Billing {
        PAID,
        DECLINED
    }
}
