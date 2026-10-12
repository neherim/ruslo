package io.github.neherim.ruslo.examples.cookbook.testing;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Techniques for {@link ProcessEngineEmulator}: the process runs in the test thread, only on command.
 * {@code @ProcessEngineTest} disables the dispatcher and resets the engine clock to the initial time before each
 * test.
 */
@ProcessEngineTest
@SpringBootTest(classes = CookbookApplication.class)
class EmulatorTest {

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void runOneStepExecutesSingleTransition() {
        var processId = startReview();

        var afterStep = emulator.runOneStep(processId);

        assertTrue(afterStep.state().is(ReviewDefinition.State.WAITING_FOR_REVIEW));
        // An await with no due events: the next step changes nothing
        assertTrue(emulator.runOneStep(processId).state().is(ReviewDefinition.State.WAITING_FOR_REVIEW));
    }

    @Test
    void runUntilStateStopsAtTargetAndFailsIfProcessStopsEarlier() {
        var processId = startReview();

        var waiting = emulator.runUntilState(processId, ReviewDefinition.State.WAITING_FOR_REVIEW);
        assertTrue(waiting.state().is(ReviewDefinition.State.WAITING_FOR_REVIEW));

        // The process waits for the reviewer and does not reach PUBLISHED by itself
        assertThrows(IllegalStateException.class,
                () -> emulator.runUntilState(processId, ReviewDefinition.State.PUBLISHED));
    }

    @Test
    void sendMessageAndRunUntilStopDeliversMessage() {
        var processId = startReview();
        emulator.runUntilStop(processId);

        var published = emulator.sendMessageAndRunUntilStop(processId, ReviewDefinition.APPROVED, "alice");

        assertEquals(ProcessStatus.COMPLETED, published.status());
        assertTrue(published.state().is(ReviewDefinition.State.PUBLISHED));
    }

    @Test
    void fastForwardTimeTriggersTimeout() {
        var processId = startReview();
        emulator.runUntilStop(processId);

        emulator.fastForwardTime(ReviewDefinition.REVIEW_DEADLINE.minus(Duration.ofMinutes(1)));
        assertTrue(emulator.runUntilStop(processId).state().is(ReviewDefinition.State.WAITING_FOR_REVIEW));

        emulator.fastForwardTime(Duration.ofMinutes(1));
        assertTrue(emulator.runUntilStop(processId).state().is(ReviewDefinition.State.EXPIRED));
    }

    @Test
    void setTimeMovesClockToExactInstant() {
        var processId = startReview();
        var waiting = emulator.runUntilStop(processId);

        // The deadline counts from entering the await: the entry time is in the state history
        var enteredAt = waiting.stateHistory().getLast().changedAt();
        emulator.setTime(enteredAt.plus(ReviewDefinition.REVIEW_DEADLINE));

        assertTrue(emulator.runUntilStop(processId).state().is(ReviewDefinition.State.EXPIRED));
    }

    private UUID startReview() {
        return processEngine.newProcess(ReviewDefinition.TYPE)
            .variable(ReviewDefinition.DOCUMENT_ID, "doc-1")
            .start();
    }
}
