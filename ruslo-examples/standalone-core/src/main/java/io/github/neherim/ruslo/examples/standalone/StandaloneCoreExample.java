package io.github.neherim.ruslo.examples.standalone;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessStateId;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.h2.jdbcx.JdbcDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * Ruslo without Spring: the engine is built by hand on top of a plain {@link DataSource}.
 * <p>
 * Lifecycle: {@link ProcessEngineBuilder#build()} → {@link ProcessEngine#start()} (schema migration, node
 * registration, dispatcher start) → work → {@link ProcessEngine#close()} (stops the dispatcher and workers).
 * The builder registers the transactional {@code DEFAULT} worker itself; add your own workers with
 * {@code registerWorker(...)}.
 */
public class StandaloneCoreExample implements AutoCloseable {
    private final ProcessEngine engine;

    StandaloneCoreExample(ProcessEngine engine) {
        this.engine = engine;
    }

    /**
     * Builds and starts the engine; on return the dispatcher already runs processes in the background.
     */
    public static StandaloneCoreExample start(DataSource dataSource) {
        var engine = new ProcessEngineBuilder(new PlainJdbcDatabase(dataSource))
            .addProcessDefinition(new ApprovalProcessDefinition())
            .build();
        engine.start();
        return new StandaloneCoreExample(engine);
    }

    public ProcessEngine engine() {
        return engine;
    }

    /**
     * Starts a document approval.
     */
    public UUID startApproval(String documentId, long amount) {
        return engine.newProcess(ApprovalProcessDefinition.TYPE)
            .businessKey(documentId)
            .variable(Constants.DOCUMENT_ID, documentId)
            .variable(Constants.AMOUNT, amount)
            .start();
    }

    /**
     * Sends the "approved" decision to the process.
     *
     * @throws MessageNotExpectedException if the process is not waiting for a decision now
     */
    public void approve(UUID processId, String approver) {
        engine.sendMessage(processId, Constants.APPROVE_MESSAGE, approver);
    }

    /**
     * Sends the "rejected" decision to the process.
     *
     * @throws MessageNotExpectedException if the process is not waiting for a decision now
     */
    public void reject(UUID processId, String reason) {
        engine.sendMessage(processId, Constants.REJECT_MESSAGE, reason);
    }

    /**
     * Stops the engine dispatcher and workers.
     */
    @Override
    public void close() {
        engine.close();
    }

    /**
     * Runs the example on in-memory H2: approves one document and prints the final state.
     */
    public static void main(String[] args) throws InterruptedException {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:standalone;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");

        try (var example = StandaloneCoreExample.start(dataSource)) {
            var processId = example.startApproval("invoice-42", 500);
            // The process runs in the background; the message is accepted only once it reaches the await.
            while (!example.stateOf(processId).is(ApprovalState.WAITING_FOR_APPROVAL)) {
                Thread.sleep(50);
            }
            example.approve(processId, "manager-1");
            while (example.stateOf(processId).is(ApprovalState.WAITING_FOR_APPROVAL)) {
                Thread.sleep(50);
            }
            System.out.println("Process finished in state " + example.stateOf(processId).name());
        }
    }

    private ProcessStateId stateOf(UUID processId) {
        return engine.processes().byId(processId).singleDetails().orElseThrow().state();
    }
}
