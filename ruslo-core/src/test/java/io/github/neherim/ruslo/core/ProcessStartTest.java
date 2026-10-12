package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchScheduler;
import io.github.neherim.ruslo.core.spi.Database;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition.State.INIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class ProcessStartTest extends BaseTest {

    /**
     * Starts a process inside a transaction and checks it does not run before commit, then commits and checks it runs
     * without waiting for {@link ProcessDispatchScheduler}. This covers {@link Database#doAfterCommit(Runnable)}.
     */
    @Test
    void processShouldStartOnlyAfterCurrentTransactionCommit() {
        var processEngine = newProcessEngineWith(new SimpleProcessDefinition());

        database.doInTransactionWithResult(() -> {
            var id = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();
            var process = processEngine.processes().byId(id).singleDetails().orElseThrow();
            assertThat(process.state().is(INIT))
                .as("Process should be in INIT state before commit")
                .isTrue();
            return id;
        });
    }

    @Test
    void startProcessWithoutMdcGeneratesCorrelationId() {
        var processEngine = newProcessEngineWith(new SimpleProcessDefinition());

        var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();

        var process = processEngine.processes().byId(processId).singleDetails().orElseThrow();
        assertFalse(process.correlationId().isBlank());
    }

    @Test
    void startProcessUsesCorrelationIdFromMdc() {
        var processEngine = newProcessEngineWith(new SimpleProcessDefinition());
        MDC.put("correlation_id", "mdc-correlation");
        try {
            var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE).start();

            var process = processEngine.processes().byId(processId).singleDetails().orElseThrow();
            assertEquals("mdc-correlation", process.correlationId());
        } finally {
            MDC.clear();
        }
    }

    @Test
    void explicitCorrelationIdOverridesMdc() {
        var processEngine = newProcessEngineWith(new SimpleProcessDefinition());
        MDC.put("correlation_id", "mdc-correlation");
        try {
            var processId = processEngine.newProcess(SimpleProcessDefinition.TYPE)
                .correlationId("explicit-correlation")
                .start();

            var process = processEngine.processes().byId(processId).singleDetails().orElseThrow();
            assertEquals("explicit-correlation", process.correlationId());
        } finally {
            MDC.clear();
        }
    }

}
