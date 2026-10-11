package io.github.neherim.ruslo.examples.cookbook.subscription;

import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Recipe: a long-lived process, a monthly subscription charge until the customer cancels.
 * <p>
 * Task: the customer paid for the first period at sign-up. After that the card is charged every 30 days. If the bank
 * declines a charge, the customer has a week to attach another card, otherwise the subscription is suspended. The
 * subscription can be cancelled at any time.
 * <p>
 * How it works:
 * <ul>
 *   <li>The process starts right in await {@link State#ACTIVE}: there is no step, the first period is already paid.
 *       The process business key is the subscription id.</li>
 *   <li>{@code every} in {@link State#ACTIVE} charges the fee. A successful charge targets the await itself: "wait
 *       for the next period". A bank decline leads to {@link State#PAST_DUE}.</li>
 *   <li>Message {@link #CANCEL} is declared in the same await, next to {@code every}: whichever event comes first is
 *       handled. A cancelled subscription is not charged again.</li>
 *   <li>{@link State#PAST_DUE} waits for a new card ({@link #CARD_UPDATED}), a cancellation or the grace period
 *       {@code timeout}. The new card handler tries to charge the debt at once: success returns the subscription to
 *       {@link State#ACTIVE}, another decline keeps it waiting, and the deadline does not move.</li>
 *   <li>The charge idempotency key is the subscription id, the period number and the card
 *       ({@link BillingHandlers}). A handler retry after a network failure does not charge the period twice, and a
 *       new card gets a new key: the provider remembers a decline by key too. Provider failures are retried through
 *       {@code onError}.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>{@code every} is an interval, not a calendar: "every 30 days", not "on the 5th of every month". The next
 *       charge is scheduled from the time of handling, so delays accumulate: retries after a provider failure shift
 *       the date. Periods missed while the application was down are not charged in a burst: one charge runs. If the
 *       charge date matters by contract, keep the schedule in billing and let the process only react to it.</li>
 *   <li>Returning from {@link State#PAST_DUE} to {@link State#ACTIVE} is a new entry into the await: the next charge
 *       counts from the debt payment. Here this is intended: the charge date moves to the payment date.</li>
 *   <li>Do not keep payment history in variables: all variables are stored as one JSON in the process row and are
 *       read on every event. Over years of a subscription they would grow without bound. The process keeps only
 *       the paid period number; the history lives in billing.</li>
 *   <li>"Keep waiting" does not write a state history row: monthly charges do not bloat the history, while
 *       {@link State#ACTIVE} ↔ {@link State#PAST_DUE} transitions do write rows.</li>
 *   <li>Subscriptions live for years, while the definition changes with the application. Do not remove or rename
 *       states that hold live processes, and change variable classes compatibly ({@code docs/concepts.md},
 *       "Definition evolution").</li>
 *   <li>Here cancellation takes effect at once. To cancel at the end of the paid period, the cancel handler stores
 *       a flag and returns "keep waiting", and the next {@code every} sees the flag and ends the subscription
 *       instead of charging.</li>
 * </ul>
 */
@Component
public class SubscriptionDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("SUBSCRIPTION");
    public static final Duration BILLING_PERIOD = Duration.ofDays(30);
    public static final Duration GRACE_PERIOD = Duration.ofDays(7);
    public static final VariableType<String> CARD_TOKEN = VariableType.of("cardToken", String.class);
    public static final VariableType<BigDecimal> PRICE = VariableType.of("price", BigDecimal.class);
    public static final VariableType<Integer> PAID_PERIODS = VariableType.of("paidPeriods", Integer.class);
    public static final VariableType<String> CANCELLATION_REASON =
            VariableType.of("cancellationReason", String.class);
    public static final MessageType<String> CARD_UPDATED = MessageType.of("cardUpdated", String.class);
    public static final MessageType<String> CANCEL = MessageType.of("cancel", String.class);

    private final BillingHandlers billingHandlers;

    public SubscriptionDefinition(BillingHandlers billingHandlers) {
        this.billingHandlers = billingHandlers;
    }

    /** Describes a subscription: periodic charge, past due with a grace period, and cancellation. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .onError(ProcessErrorHandler.exponential(5, Duration.ofMinutes(1), Duration.ofHours(1)))
                .initialState(State.ACTIVE)
                .await(State.ACTIVE, await -> await
                        .every(BILLING_PERIOD, billingHandlers::charge, routes -> routes
                                .on(BillingHandlers.Billing.PAID, State.ACTIVE)
                                .on(BillingHandlers.Billing.DECLINED, State.PAST_DUE))
                        .message(CANCEL, billingHandlers::onCancel, State.CANCELLED))
                .await(State.PAST_DUE, await -> await
                        .message(CARD_UPDATED, billingHandlers::onCardUpdated, routes -> routes
                                .on(BillingHandlers.Billing.PAID, State.ACTIVE)
                                .on(BillingHandlers.Billing.DECLINED, State.PAST_DUE))
                        .message(CANCEL, billingHandlers::onCancel, State.CANCELLED)
                        .timeout(GRACE_PERIOD, State.SUSPENDED))
                .terminal(State.CANCELLED)
                .terminal(State.SUSPENDED)
                .build();
    }

    public enum State implements ProcessState {
        ACTIVE,
        PAST_DUE,
        CANCELLED,
        SUSPENDED
    }
}
