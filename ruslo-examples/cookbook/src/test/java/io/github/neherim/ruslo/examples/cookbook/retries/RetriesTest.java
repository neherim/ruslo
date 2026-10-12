package io.github.neherim.ruslo.examples.cookbook.retries;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class RetriesTest {
    private static final String RECIPIENT = "user@example.com";

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @MockitoBean
    private NotificationGateway notificationGateway;

    @Test
    void retriesTemporaryFailureAndSends() {
        doThrow(new IllegalStateException("Gateway is unavailable"))
            .doThrow(new IllegalStateException("Gateway is unavailable"))
            .doNothing()
            .when(notificationGateway)
            .send(any(), any());
        var processId = startNotification(RECIPIENT);

        // The first attempt failed: a retry is scheduled in 1 second
        var firstRetry = emulator.runUntilStop(processId);
        assertEquals(1, firstRetry.attempt());
        assertTrue(firstRetry.scheduledAt().isPresent());

        // The emulator does not run the step before the retry time: the test advances time explicitly
        emulator.fastForwardTime(Duration.ofSeconds(1));
        var secondRetry = emulator.runUntilStop(processId);
        assertEquals(2, secondRetry.attempt());

        emulator.fastForwardTime(Duration.ofSeconds(2));
        var sent = emulator.runUntilStop(processId);

        assertTrue(sent.state().is(NotificationDefinition.State.SENT));
        assertEquals(0, sent.attempt());
        verify(notificationGateway, times(3)).send(RECIPIENT, "Your order has shipped");
    }

    @Test
    void movesToFallbackStateWhenGatewayStaysDown() {
        doThrow(new IllegalStateException("Gateway is unavailable"))
            .when(notificationGateway)
            .send(any(), any());
        var processId = startNotification(RECIPIENT);

        emulator.runUntilStop(processId);
        emulator.fastForwardTime(Duration.ofSeconds(1));
        emulator.runUntilStop(processId);
        emulator.fastForwardTime(Duration.ofSeconds(2));
        emulator.runUntilStop(processId);
        emulator.fastForwardTime(Duration.ofSeconds(4));
        var failed = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.COMPLETED, failed.status());
        assertTrue(failed.state().is(NotificationDefinition.State.DELIVERY_FAILED));
        verify(notificationGateway, times(1 + NotificationDefinition.MAX_RETRIES)).send(any(), any());
    }

    @Test
    void doesNotRetryPermanentFailure() {
        doThrow(new InvalidRecipientException("not-an-address"))
            .when(notificationGateway)
            .send(any(), any());
        var processId = startNotification("not-an-address");

        var failed = emulator.runUntilStop(processId);

        assertTrue(failed.state().is(NotificationDefinition.State.DELIVERY_FAILED));
        verify(notificationGateway, times(1)).send(any(), any());
    }

    private UUID startNotification(String recipient) {
        return processEngine.newProcess(NotificationDefinition.TYPE)
            .variable(NotificationDefinition.RECIPIENT, recipient)
            .variable(NotificationDefinition.TEXT, "Your order has shipped")
            .start();
    }
}
