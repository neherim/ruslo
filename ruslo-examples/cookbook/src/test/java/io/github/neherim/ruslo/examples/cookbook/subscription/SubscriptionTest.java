package io.github.neherim.ruslo.examples.cookbook.subscription;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class SubscriptionTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void chargesEveryPeriodUntilCancelled() {
        var processId = startSubscription("tok_visa");

        for (int i = 0; i < 3; i++) {
            emulator.fastForwardTime(SubscriptionDefinition.BILLING_PERIOD);
            var active = emulator.runUntilStop(processId);
            assertTrue(active.state().is(SubscriptionDefinition.State.ACTIVE));
        }
        var cancelled = emulator.sendMessageAndRunUntilStop(processId, SubscriptionDefinition.CANCEL, "Too expensive");

        assertTrue(cancelled.state().is(SubscriptionDefinition.State.CANCELLED));
    }

    @Test
    void suspendsWhenChargeDeclined() {
        var processId = startSubscription(SubscriptionDefinition.DECLINED_CARD);
        emulator.fastForwardTime(SubscriptionDefinition.BILLING_PERIOD);

        var suspended = emulator.runUntilStop(processId);

        assertTrue(suspended.state().is(SubscriptionDefinition.State.SUSPENDED));
    }

    private UUID startSubscription(String cardToken) {
        var processId = processEngine.newProcess(SubscriptionDefinition.TYPE)
            .businessKey("subscription-" + UUID.randomUUID())
            .variable(SubscriptionDefinition.CARD_TOKEN, cardToken)
            .start();
        emulator.runUntilStop(processId);
        return processId;
    }
}
