package io.github.neherim.ruslo.examples.cookbook.subscription;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class SubscriptionTest {
    private static final BigDecimal PRICE = new BigDecimal("9.99");

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @MockitoSpyBean
    private BillingGateway billingGateway;

    @Test
    void chargesEveryPeriodUntilCancelled() {
        var subscriptionId = newSubscriptionId();
        var processId = startSubscription(subscriptionId, "tok_visa");

        for (int i = 0; i < 3; i++) {
            emulator.fastForwardTime(SubscriptionDefinition.BILLING_PERIOD);
            var active = emulator.runUntilStop(processId);
            assertTrue(active.state().is(SubscriptionDefinition.State.ACTIVE));
        }
        var cancelled = emulator.sendMessageAndRunUntilStop(processId, SubscriptionDefinition.CANCEL, "Too expensive");

        assertTrue(cancelled.state().is(SubscriptionDefinition.State.CANCELLED));
        // The first period was paid at sign-up; the process charged three more
        verify(billingGateway).charge(subscriptionId + "/2/tok_visa", "tok_visa", PRICE);
        verify(billingGateway).charge(subscriptionId + "/4/tok_visa", "tok_visa", PRICE);
        verify(billingGateway, times(3)).charge(any(), any(), any());
        assertEquals(4,
                cancelled.variables().get(SubscriptionDefinition.PAID_PERIODS).orElseThrow());

        emulator.fastForwardTime(SubscriptionDefinition.BILLING_PERIOD);
        emulator.runUntilStop(processId);
        verify(billingGateway, times(3)).charge(any(), any(), any());
    }

    @Test
    void returnsToActiveAfterCardUpdate() {
        var subscriptionId = newSubscriptionId();
        var processId = startSubscription(subscriptionId, BillingGateway.DECLINED_CARD);
        emulator.fastForwardTime(SubscriptionDefinition.BILLING_PERIOD);
        var pastDue = emulator.runUntilStop(processId);
        assertTrue(pastDue.state().is(SubscriptionDefinition.State.PAST_DUE));

        // The new card gets a new key: the provider remembered the decline under the old card's key
        var active = emulator.sendMessageAndRunUntilStop(processId, SubscriptionDefinition.CARD_UPDATED, "tok_visa");

        assertTrue(active.state().is(SubscriptionDefinition.State.ACTIVE));
        assertEquals(2,
                active.variables().get(SubscriptionDefinition.PAID_PERIODS).orElseThrow());
        verify(billingGateway).charge(subscriptionId + "/2/tok_visa", "tok_visa", PRICE);

        // The next charge counts from the debt payment
        emulator.fastForwardTime(SubscriptionDefinition.BILLING_PERIOD);
        emulator.runUntilStop(processId);
        verify(billingGateway).charge(eq(subscriptionId + "/3/tok_visa"), any(), any());
    }

    @Test
    void suspendsAfterGracePeriod() {
        var processId = startSubscription(newSubscriptionId(), BillingGateway.DECLINED_CARD);
        emulator.fastForwardTime(SubscriptionDefinition.BILLING_PERIOD);
        emulator.runUntilStop(processId);

        // Another decline keeps the subscription waiting; the grace period deadline does not move
        emulator.fastForwardTime(Duration.ofDays(3));
        var stillPastDue = emulator.sendMessageAndRunUntilStop(
                processId, SubscriptionDefinition.CARD_UPDATED, BillingGateway.DECLINED_CARD);
        assertTrue(stillPastDue.state().is(SubscriptionDefinition.State.PAST_DUE));

        emulator.fastForwardTime(Duration.ofDays(4));
        var suspended = emulator.runUntilStop(processId);

        assertTrue(suspended.state().is(SubscriptionDefinition.State.SUSPENDED));
    }

    private UUID startSubscription(String subscriptionId, String cardToken) {
        var processId = processEngine.newProcess(SubscriptionDefinition.TYPE)
                .businessKey(subscriptionId)
                .variable(SubscriptionDefinition.CARD_TOKEN, cardToken)
                .variable(SubscriptionDefinition.PRICE, PRICE)
                .variable(SubscriptionDefinition.PAID_PERIODS, 1)
                .start();
        emulator.runUntilStop(processId);
        return processId;
    }

    private static String newSubscriptionId() {
        return "subscription-" + UUID.randomUUID();
    }
}
