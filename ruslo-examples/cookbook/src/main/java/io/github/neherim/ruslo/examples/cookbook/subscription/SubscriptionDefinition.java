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
import io.github.neherim.ruslo.core.api.definition.TimerType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * Recipe: a long-lived process, a monthly subscription charge until the customer cancels.
 * <p>
 * Task: the customer paid for the first month at sign-up. After that the card is charged on the same day every month.
 * If the bank declines a charge, the subscription is suspended. The subscription can be cancelled at any time.
 * <p>
 * How it works:
 * <ul>
 *   <li>The process starts right in await {@link State#ACTIVE}: there is no step, the first period is already paid.
 *       The process business key is the subscription id.</li>
 *   <li>The caller passes the sign-up date in variable {@link #SUBSCRIBED_AT} and the end of the paid period,
 *       computed by {@link #nextChargeAt}, in {@link #PAID_UNTIL}, and sets process timer {@link #NEXT_CHARGE} to
 *       the same moment. {@code at(NEXT_CHARGE, ...)} in {@link State#ACTIVE} charges the fee then.</li>
 *   <li>The fired timer is cancelled before the handler runs. A successful charge moves {@link #PAID_UNTIL} to the
 *       next date, sets the timer to it again and targets the await itself: "wait for the next date". So the charges
 *       make a schedule. A bank decline leads to {@link State#SUSPENDED}.</li>
 *   <li>Message {@link #CANCEL} is declared in the same await, next to {@code at}: whichever event comes first is
 *       handled. A cancelled subscription is not charged again.</li>
 *   <li>Billing failures are retried through {@code onError}. A retried charge must not charge the period twice:
 *       pass the provider an idempotency key (recipe {@code externalcall}).</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>The schedule continues only while the handler sets the timer again. A handler that does not set it does not
 *       fail: the subscription keeps waiting for {@link #CANCEL}. Use {@code at} for calendar dates; {@code every} is
 *       an interval, "every 30 days", not "on the 5th of every month".</li>
 *   <li>The handler does not get the moment the timer fired at, so the paid period is kept in a variable: it is
 *       business data, and the next date is counted from it.</li>
 *   <li>The next date is counted from the sign-up date, not from the previous charge date: otherwise the day drifts
 *       after a short month (31 January, 28 February, 28 March). It is not counted from the time of handling either:
 *       a late or retried charge does not shift the schedule, and after downtime every missed month is charged at
 *       once.</li>
 *   <li>Do not keep payment history in variables: all variables are stored as one JSON in the process row and are
 *       read on every event. The history lives in billing.</li>
 *   <li>Subscriptions live for years, while the definition changes with the application. Do not remove or rename
 *       states that hold live processes ({@code docs/concepts.md}, "Definition evolution").</li>
 * </ul>
 */
@Component
public class SubscriptionDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("SUBSCRIPTION");
    public static final VariableType<Instant> SUBSCRIBED_AT = VariableType.of("subscribedAt", Instant.class);
    public static final VariableType<Instant> PAID_UNTIL = VariableType.of("paidUntil", Instant.class);
    public static final TimerType NEXT_CHARGE = TimerType.of("nextCharge");
    public static final VariableType<String> CARD_TOKEN = VariableType.of("cardToken", String.class);
    public static final VariableType<String> CANCELLATION_REASON = VariableType.of("cancellationReason", String.class);
    public static final MessageType<String> CANCEL = MessageType.of("cancel", String.class);

    /** Stands for a card the bank always declines. */
    public static final String DECLINED_CARD = "tok_declined";

    private static final Logger log = LoggerFactory.getLogger(SubscriptionDefinition.class);

    /** Describes a subscription: monthly charge until cancellation or a bank decline. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .onError(ProcessErrorHandler.exponential(5, Duration.ofMinutes(1), Duration.ofHours(1)))
            .initialState(State.ACTIVE)
            .await(State.ACTIVE, await -> await
                .at(NEXT_CHARGE, SubscriptionDefinition::charge, routes -> routes
                    .on(Billing.PAID, State.ACTIVE)
                    .on(Billing.DECLINED, State.SUSPENDED))
                .message(CANCEL, SubscriptionDefinition::onCancel, State.CANCELLED))
            .terminal(State.CANCELLED)
            .terminal(State.SUSPENDED)
            .build();
    }

    /**
     * Returns the first charge date after {@code previous}: the sign-up date plus a whole number of calendar months,
     * in UTC. A day missing in a short month moves to its last day only for that month.
     */
    public static Instant nextChargeAt(Instant subscribedAt, Instant previous) {
        var start = subscribedAt.atZone(ZoneOffset.UTC);
        for (var months = 1;; months++) {
            var next = start.plusMonths(months).toInstant();
            if (next.isAfter(previous)) return next;
        }
    }

    /** Stands for a charge in billing; a successful charge extends the paid period and sets the next charge. */
    private static Billing charge(ProcessContext context) {
        var cardToken = context.getVariable(CARD_TOKEN);
        var paid = !DECLINED_CARD.equals(cardToken);
        log.info("Subscription {} charged to {}: paid={}", context.businessKey().orElseThrow(), cardToken, paid);
        if (!paid) return Billing.DECLINED;
        var paidUntil = nextChargeAt(context.getVariable(SUBSCRIBED_AT), context.getVariable(PAID_UNTIL));
        context.setVariable(PAID_UNTIL, paidUntil);
        context.setTimer(NEXT_CHARGE, paidUntil);
        return Billing.PAID;
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
