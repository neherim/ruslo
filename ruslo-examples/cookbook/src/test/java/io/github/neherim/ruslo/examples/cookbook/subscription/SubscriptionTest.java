package io.github.neherim.ruslo.examples.cookbook.subscription;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class SubscriptionTest {
    private static final Instant SIGN_UP = Instant.parse("2026-01-31T09:00:00Z");
    private static final Instant FIRST_CHARGE = Instant.parse("2026-02-28T09:00:00Z");

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void chargesOnSignUpDayEveryMonthUntilCancelled() {
        var processId = startSubscription("tok_visa");

        var chargeDates = List.of(FIRST_CHARGE, Instant.parse("2026-03-31T09:00:00Z"),
                Instant.parse("2026-04-30T09:00:00Z"), Instant.parse("2026-05-31T09:00:00Z"));
        for (int i = 0; i < 3; i++) {
            var chargeAt = chargeDates.get(i);
            emulator.setTime(chargeAt.minusSeconds(1));
            assertEquals(chargeAt, emulator.runUntilStop(processId).nextRunAt().orElseThrow());
            emulator.setTime(chargeAt);
            var active = emulator.runUntilStop(processId);
            assertTrue(active.state().is(SubscriptionDefinition.State.ACTIVE));
            assertEquals(chargeDates.get(i + 1),
                    active.variables().get(SubscriptionDefinition.NEXT_CHARGE_AT).orElseThrow());
        }
        var cancelled = emulator.sendMessageAndRunUntilStop(processId, SubscriptionDefinition.CANCEL, "Too expensive");

        assertTrue(cancelled.state().is(SubscriptionDefinition.State.CANCELLED));
    }

    @Test
    void suspendsWhenChargeDeclined() {
        var processId = startSubscription(SubscriptionDefinition.DECLINED_CARD);
        emulator.setTime(FIRST_CHARGE);

        var suspended = emulator.runUntilStop(processId);

        assertTrue(suspended.state().is(SubscriptionDefinition.State.SUSPENDED));
    }

    private UUID startSubscription(String cardToken) {
        emulator.setTime(SIGN_UP);
        var processId = processEngine.newProcess(SubscriptionDefinition.TYPE)
            .businessKey("subscription-" + UUID.randomUUID())
            .variable(SubscriptionDefinition.CARD_TOKEN, cardToken)
            .variable(SubscriptionDefinition.SUBSCRIBED_AT, SIGN_UP)
            .variable(SubscriptionDefinition.NEXT_CHARGE_AT, SubscriptionDefinition.nextChargeAt(SIGN_UP, SIGN_UP))
            .start();
        emulator.runUntilStop(processId);
        return processId;
    }
}
