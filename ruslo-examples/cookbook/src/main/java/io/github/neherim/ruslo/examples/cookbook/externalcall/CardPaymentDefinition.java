package io.github.neherim.ruslo.examples.cookbook.externalcall;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Recipe: call a payment API without charging twice.
 * <p>
 * Task: charge a card through a payment provider. The call is slow, so it runs on a non-transactional worker. Such a
 * step runs at least once: after a response timeout, a node crash or a lost lease it runs again. The retry must not
 * charge the money a second time.
 * <p>
 * How it works:
 * <ul>
 *   <li>Step {@link ChargeCardStep} runs on {@code io-worker} (declared in {@code application.yml}, see recipe
 *       {@code ioworker}).</li>
 *   <li>The step passes the provider an idempotency key, the process id. It is the same in every retry of the
 *       step, and the provider answers a repeated request with the charge already made.</li>
 *   <li>Transient errors are retried with a growing delay. When retries are exhausted, the process stops in
 *       {@code FAILED}: it is unknown whether the money was charged, so a human must decide, not an automatic
 *       decline.</li>
 *   <li>A bank decline is not an error but outcome {@link ChargeCardStep.Result#DECLINED}: retrying it is
 *       pointless.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Do not generate the key in the step, even if you store it in a variable: variables of a failed attempt are
 *       not saved, and the retry gets a new key. Take the key from something that does not change between retries:
 *       the process id, the business key or a variable written by a previous step.</li>
 *   <li>If the process charges several times (for example, paying again with another card after a decline), the
 *       process id is no longer unique per charge: append a payment attempt number incremented by a previous
 *       step.</li>
 *   <li>The provider keeps keys for a limited time (24 hours at Stripe). Retries beyond that are dangerous again:
 *       limit their number and delay.</li>
 *   <li>If the provider does not support idempotency keys, look up an existing payment by your own reference (the
 *       order id) before charging, and charge only if there is none.</li>
 *   <li>Application DB work in a non-transactional step is not atomic with the process transition. Mark the order
 *       as paid in the next step on {@code DEFAULT}.</li>
 * </ul>
 */
@Component
public class CardPaymentDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("CARD_PAYMENT");
    public static final WorkerId IO_WORKER = new WorkerId("io-worker");
    public static final VariableType<String> CARD_TOKEN = VariableType.of("cardToken", String.class);
    public static final VariableType<BigDecimal> AMOUNT = VariableType.of("amount", BigDecimal.class);
    public static final VariableType<String> CHARGE_ID = VariableType.of("chargeId", String.class);

    static final int MAX_RETRIES = 5;

    private final ChargeCardStep chargeCardStep;

    public CardPaymentDefinition(ChargeCardStep chargeCardStep) {
        this.chargeCardStep = chargeCardStep;
    }

    /** Describes a card payment: one charge on {@code io-worker} with retries on failures. */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
                .type(TYPE)
                .onError(ProcessErrorHandler.exponential(MAX_RETRIES, Duration.ofSeconds(5), Duration.ofMinutes(5)))
                .initialState(State.CHARGING)
                .step(State.CHARGING, chargeCardStep, routes -> routes
                                .on(ChargeCardStep.Result.APPROVED, State.PAID)
                                .on(ChargeCardStep.Result.DECLINED, State.DECLINED),
                        options -> options.worker(IO_WORKER))
                .terminal(State.PAID)
                .terminal(State.DECLINED)
                .build();
    }

    public enum State implements ProcessState {
        CHARGING,
        PAID,
        DECLINED
    }
}
