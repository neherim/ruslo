package io.github.neherim.ruslo.examples.cookbook.retries;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class RetriesTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void sendsNotification() {
        var sent = emulator.runUntilStop(startNotification("ada@example.com"));

        assertEquals(NotificationDefinition.State.SENT.id(), sent.state());
    }

    @Test
    void retriesWithGrowingDelayThenFallsBack() {
        var processId = startNotification("ada" + NotificationDefinition.UNAVAILABLE_DOMAIN);

        // Each failed attempt schedules a retry: after 1, 2 and 4 seconds
        var process = emulator.runUntilStop(processId);
        for (var delay : new int[]{1, 2, 4}) {
            assertEquals(NotificationDefinition.State.SENDING.id(), process.state());
            emulator.fastForwardTime(Duration.ofSeconds(delay));
            process = emulator.runUntilStop(processId);
        }

        assertEquals(NotificationDefinition.State.DELIVERY_FAILED.id(), process.state());
    }

    @Test
    void doesNotRetryPermanentFailure() {
        var failed = emulator.runUntilStop(startNotification("+1-555-0100"));

        // No retry was scheduled: the process went to the give-up state at once
        assertEquals(NotificationDefinition.State.DELIVERY_FAILED.id(), failed.state());
        assertEquals(0, failed.attempt());
    }

    private UUID startNotification(String recipient) {
        return processEngine.newProcess(NotificationDefinition.TYPE)
            .variable(NotificationDefinition.RECIPIENT, recipient)
            .variable(NotificationDefinition.TEXT, "Your order has shipped")
            .start();
    }
}
