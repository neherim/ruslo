package io.github.neherim.ruslo.examples.standalone;

import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The engine runs without the dispatcher: {@link ProcessEngineEmulator} executes steps synchronously in the
 * test thread, so the test needs no waiting.
 */
class StandaloneCoreExampleTest {
    private StandaloneCoreExample example;
    private ProcessEngineEmulator emulator;

    @BeforeEach
    void setUp() {
        var engine = new ProcessEngineBuilder(new PlainJdbcDatabase(newDataSource()))
            .addProcessDefinition(new ApprovalProcessDefinition())
            .withDispatcherEnabled(false)
            .build();
        engine.start();
        example = new StandaloneCoreExample(engine);
        emulator = new ProcessEngineEmulator(engine);
    }

    @AfterEach
    void tearDown() {
        example.close();
    }

    @Test
    void managerApprovesSmallDocument() {
        var processId = example.startApproval("invoice-42", 500);

        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(ApprovalState.WAITING_FOR_APPROVAL));
        assertEquals("manager",
                waiting.variables().get(Constants.APPROVER_ROLE).orElseThrow());

        example.approve(processId, "manager-1");

        var approved = emulator.runUntilStop(processId);
        assertTrue(approved.state().is(ApprovalState.APPROVED));
        assertEquals(ProcessStatus.COMPLETED, approved.status());
        assertEquals("manager-1",
                approved.variables().get(Constants.APPROVED_BY).orElseThrow());
    }

    @Test
    void cfoRejectsLargeDocument() {
        var processId = example.startApproval("contract-7", 50_000);

        var waiting = emulator.runUntilStop(processId);
        assertTrue(waiting.state().is(ApprovalState.WAITING_FOR_APPROVAL));
        assertEquals("cfo",
                waiting.variables().get(Constants.APPROVER_ROLE).orElseThrow());

        example.reject(processId, "Budget exceeded");

        var rejected = emulator.runUntilStop(processId);
        assertTrue(rejected.state().is(ApprovalState.REJECTED));
        assertEquals("Budget exceeded",
                rejected.variables().get(Constants.REJECTION_REASON).orElseThrow());
    }

    private static JdbcDataSource newDataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:standalone-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }
}
