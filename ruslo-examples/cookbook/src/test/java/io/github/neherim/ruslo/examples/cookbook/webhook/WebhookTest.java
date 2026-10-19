package io.github.neherim.ruslo.examples.cookbook.webhook;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.examples.cookbook.webhook.VerificationCallback.Status;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class WebhookTest {
    private static final String EMAIL = "client@example.com";

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;
    @Autowired
    private VerificationWebhook webhook;

    @Test
    void completesVerificationFromCallbacks() {
        var applicationId = newApplicationId();
        var processId = startVerification(applicationId);
        emulator.runUntilStop(processId);

        webhook.onCallback(new VerificationCallback(applicationId, "evt-1", Status.DOCUMENTS_REQUIRED));
        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(IdentityVerificationDefinition.State.WAITING_FOR_RESULT));

        webhook.onCallback(new VerificationCallback(applicationId, "evt-2", Status.APPROVED));
        var verified = emulator.runUntilStop(processId);

        assertEquals(ProcessStatus.COMPLETED, verified.status());
        assertTrue(verified.state().is(IdentityVerificationDefinition.State.VERIFIED));
    }

    @Test
    void ignoresRepeatedCallbackWhileWaiting() {
        var applicationId = newApplicationId();
        var processId = startVerification(applicationId);
        emulator.runUntilStop(processId);
        var documentsRequired = new VerificationCallback(applicationId, "evt-1", Status.DOCUMENTS_REQUIRED);

        webhook.onCallback(documentsRequired);
        webhook.onCallback(documentsRequired);
        var waiting = emulator.runUntilStop(processId);

        assertTrue(waiting.state().is(IdentityVerificationDefinition.State.WAITING_FOR_RESULT));
        assertEquals(Optional.of(new HandledEvents(List.of("evt-1"))),
                waiting.variables().get(IdentityVerificationDefinition.HANDLED_EVENTS));
    }

    @Test
    void acknowledgesCallbackForCompletedVerification() {
        var applicationId = newApplicationId();
        var processId = startVerification(applicationId);
        emulator.runUntilStop(processId);
        var declined = new VerificationCallback(applicationId, "evt-1", Status.DECLINED);
        webhook.onCallback(declined);
        assertTrue(emulator.runUntilStop(processId).state().is(IdentityVerificationDefinition.State.REJECTED));

        assertDoesNotThrow(() -> webhook.onCallback(declined));
    }

    @Test
    void ignoresCallbackForUnknownApplication() {
        assertDoesNotThrow(
                () -> webhook.onCallback(new VerificationCallback(newApplicationId(), "evt-1", Status.APPROVED)));
    }

    private UUID startVerification(String applicationId) {
        return processEngine.newProcess(IdentityVerificationDefinition.TYPE)
            .businessKey(applicationId)
            .variable(IdentityVerificationDefinition.APPLICANT_EMAIL, EMAIL)
            .start();
    }

    private static String newApplicationId() {
        return "app-" + UUID.randomUUID();
    }
}
