package io.github.neherim.ruslo.examples.standalone;

import io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine runs with the dispatcher enabled, as in an application: processes run in the background,
 * so the test waits for the result with Awaitility.
 */
class StandaloneCoreExampleTest {
    private static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(10);

    @Test
    void managerApprovesSmallDocument() {
        try (var example = StandaloneCoreExample.start(newDataSource())) {
            var processId = example.startApproval("invoice-42", 500);

            var waiting = awaitState(example, processId, ApprovalState.WAITING_FOR_APPROVAL);
            assertEquals("manager",
                    waiting.variables().get(ApprovalProcess.APPROVER_ROLE).orElseThrow());

            example.approve(processId, "manager-1");

            var approved = awaitState(example, processId, ApprovalState.APPROVED);
            assertEquals(ProcessStatus.COMPLETED, approved.status());
            assertEquals("manager-1",
                    approved.variables().get(ApprovalProcess.APPROVED_BY).orElseThrow());
        }
    }

    @Test
    void cfoRejectsLargeDocument() {
        try (var example = StandaloneCoreExample.start(newDataSource())) {
            var processId = example.startApproval("contract-7", 50_000);

            var waiting = awaitState(example, processId, ApprovalState.WAITING_FOR_APPROVAL);
            assertEquals("cfo",
                    waiting.variables().get(ApprovalProcess.APPROVER_ROLE).orElseThrow());

            example.reject(processId, "Budget exceeded");

            var rejected = awaitState(example, processId, ApprovalState.REJECTED);
            assertTrue(rejected.state().is(ApprovalState.REJECTED));
            assertEquals("Budget exceeded",
                    rejected.variables().get(ApprovalProcess.REJECTION_REASON).orElseThrow());
        }
    }

    private static ProcessInstanceDetails awaitState(StandaloneCoreExample example, UUID processId,
                                                     ApprovalState state) {
        return await().atMost(PROCESSING_TIMEOUT).until(
                () -> example.engine().processes().byId(processId).singleDetails().orElseThrow(),
                process -> process.state().is(state));
    }

    private static JdbcDataSource newDataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:standalone-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}
