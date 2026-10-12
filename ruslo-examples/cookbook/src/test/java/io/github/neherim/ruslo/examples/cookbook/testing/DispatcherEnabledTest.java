package io.github.neherim.ruslo.examples.cookbook.testing;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.examples.cookbook.CookbookApplication;
import io.github.neherim.ruslo.spring.test.ProcessEngineTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A test with a running dispatcher: background workers run the steps, as in production. Use it to check routing to
 * workers and transactional behavior that the emulator does not reproduce.
 * <p>
 * How it works:
 * <ul>
 *   <li>Instead of {@code @ProcessEngineTest}, which disables the dispatcher, only
 *       {@link ProcessEngineTestConfiguration} is imported: the emulator and the controlled clock are there, and the
 *       dispatcher runs.</li>
 *   <li>Steps run asynchronously, so the test waits for the result with Awaitility.</li>
 *   <li>Timers are still advanced with {@link ProcessEngineEmulator#fastForwardTime}; a short
 *       {@code ruslo.dispatcher.max-interval} lets the dispatcher notice a due time quickly.</li>
 *   <li>A separate database and {@link DirtiesContext}: the background dispatcher must not pick up processes of
 *       tests that drive execution through the emulator, and must not keep running after this test.</li>
 * </ul>
 */
@ImportAutoConfiguration(ProcessEngineTestConfiguration.class)
@SpringBootTest(
        classes = CookbookApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:cookbook-dispatcher;DB_CLOSE_DELAY=-1",
                "ruslo.dispatcher.enabled=true",
                "ruslo.dispatcher.min-interval=50ms",
                "ruslo.dispatcher.max-interval=100ms"
        }
)
@DirtiesContext
class DispatcherEnabledTest {
    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ProcessEngine processEngine;
    @Autowired
    private ProcessEngineEmulator emulator;

    @Test
    void publishesDocumentAfterApproval() {
        var processId = startReview();
        awaitState(processId, ReviewDefinition.State.WAITING_FOR_REVIEW);

        processEngine.sendMessage(processId, ReviewDefinition.APPROVED, "alice");

        var published = awaitState(processId, ReviewDefinition.State.PUBLISHED);
        assertEquals(ProcessStatus.COMPLETED, published.status());
    }

    @Test
    void expiresReviewAfterDeadline() {
        var processId = startReview();
        awaitState(processId, ReviewDefinition.State.WAITING_FOR_REVIEW);

        emulator.fastForwardTime(ReviewDefinition.REVIEW_DEADLINE);

        var expired = awaitState(processId, ReviewDefinition.State.EXPIRED);
        assertEquals(ProcessStatus.COMPLETED, expired.status());
    }

    private UUID startReview() {
        return processEngine.newProcess(ReviewDefinition.TYPE)
            .variable(ReviewDefinition.DOCUMENT_ID, "doc-1")
            .start();
    }

    private ProcessInstanceDetails awaitState(UUID processId, ReviewDefinition.State state) {
        return await().atMost(PROCESSING_TIMEOUT)
            .until(
                    () -> processEngine.processes().byId(processId).singleDetails().orElseThrow(),
                    process -> process.state().is(state));
    }
}
