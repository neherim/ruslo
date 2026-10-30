package io.github.neherim.ruslo.examples.cookbook.externalcall;

import io.github.neherim.ruslo.core.api.definition.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Recipe: a slow external call on a non-transactional worker, without charging twice.
 * <p>
 * Task: charge a card through a payment provider. The call takes seconds. On the transactional {@code DEFAULT}
 * worker the step would hold an open transaction and a DB connection all that time, and a few slow calls would
 * exhaust the connection pool. Without a transaction the step runs at least once: after a response timeout, a node
 * crash or a lost lease it runs again, and the retry must not charge the money a second time.
 * <p>
 * How it works:
 * <ul>
 *   <li>Worker {@code io-worker} is declared in {@code application.yml} with
 *       {@code execution-mode: non_transactional}: a step on it runs without a transaction, a DB connection is
 *       taken only to save the result. It can also be declared as a {@code WorkerSpec} bean.</li>
 *   <li>{@code max-concurrency} limits concurrent steps of the worker on a node: it protects the provider from
 *       overload, and a slow provider does not occupy {@code DEFAULT} slots.</li>
 *   <li>Step {@link ChargeCardStep} is assigned to the worker by the second argument of {@code step(...)}; other steps
 *       and all await handlers stay on {@code DEFAULT}.</li>
 *   <li>The step passes the provider an idempotency key, the process id. It is the same in every retry of the
 *       step, and the provider answers a repeated request with the charge already made.</li>
 *   <li>Transient errors are retried with a growing delay. When retries are exhausted, the process stops in
 *       {@code FAILED}: it is unknown whether the money was charged, so a human must decide, not an automatic
 *       decline. A bank decline is not an error but outcome {@link ChargeCardStep.Result#DECLINED}.</li>
 * </ul>
 * <p>
 * Pitfalls:
 * <ul>
 *   <li>Do not generate the key in the step, even if you store it in a variable: variables of a failed attempt are
 *       not saved, and the retry gets a new key. Take the key from something that does not change between retries:
 *       the process id, the business key or a variable written by a previous step (for example, a payment attempt
 *       number if the process charges several times).</li>
 *   <li>Application DB changes from a non-transactional step are not atomic with the process transition. Move DB
 *       work to neighbouring steps on {@code DEFAULT}.</li>
 *   <li>The provider keeps keys for a limited time (24 hours at Stripe). Retries beyond that are dangerous again:
 *       limit their number and delay.</li>
 * </ul>
 */
@Component
public class CardPaymentDefinition implements ProcessDefinition {
    public static final ProcessType TYPE = ProcessType.of("CARD_PAYMENT");
    public static final WorkerId IO_WORKER = new WorkerId("io-worker");
    public static final VariableType<BigDecimal> AMOUNT = VariableType.of("amount", BigDecimal.class);

    private final ChargeCardStep chargeCardStep;

    public CardPaymentDefinition(ChargeCardStep chargeCardStep) {
        this.chargeCardStep = chargeCardStep;
    }

    /**
     * Describes a card payment: one charge on {@code io-worker} with retries on failures.
     */
    @Override
    public ProcessSpec define(ProcessSpecBuilder builder) {
        return builder
            .type(TYPE)
            .onError(ProcessErrorHandler.exponential(5, Duration.ofSeconds(5), Duration.ofMinutes(5)))
            .initialState(State.CHARGING)
            .step(State.CHARGING, IO_WORKER, chargeCardStep, routes -> routes
                .on(ChargeCardStep.Result.APPROVED, State.PAID)
                .on(ChargeCardStep.Result.DECLINED, State.DECLINED))
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
