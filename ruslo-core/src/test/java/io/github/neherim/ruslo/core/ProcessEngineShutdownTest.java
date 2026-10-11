package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.github.neherim.ruslo.core.TestAwait.awaitLatch;
import static io.github.neherim.ruslo.core.TestAwait.join;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ProcessEngineShutdownTest extends BaseTest {

    @Test
    void shutdownDrainsAcceptedWorkerBeforeUnregisteringOwner() {
        var workerId = new WorkerId("shutdown-worker");
        var type = ProcessType.of("SHUTDOWN_DRAIN");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ProcessEngine engine = new ProcessEngineBuilder(database)
                .withDatabaseMigrationEnabled(false)
                .withInstanceHeartbeatInterval(Duration.ofMillis(50))
                .withInstanceTimeout(Duration.ofMillis(200))
                .registerWorker(WorkerSpec.builder(workerId)
                        .nonTransactional()
                        .maxConcurrency(1)
                        .customExecutor(executor)
                        .build())
                .addProcessDefinition(blockingDefinition(type, workerId, entered, release))
                .build()
                .start();

        try {
            var processId = engine.newProcess(type).start();
            awaitLatch(entered, "Accepted worker task should start");

            var shutdown = Thread.ofVirtual().start(engine::close);
            awaitWorkerDrain(shutdown);
            assertEquals(1, instanceCount(), "Owner must remain registered while worker drains");

            release.countDown();
            join(shutdown, "Engine shutdown should finish after worker task completes");

            assertEquals(0, instanceCount());
            assertEquals(io.github.neherim.ruslo.core.api.view.ProcessStatus.COMPLETED,
                engine.processes().byId(processId).singleDetails().orElseThrow().status());
        } finally {
            release.countDown();
            if (engine.isStarted()) {
                engine.close();
            }
            executor.shutdownNow();
        }
    }

    private ProcessDefinition blockingDefinition(ProcessType type, WorkerId workerId,
                                                  CountDownLatch entered, CountDownLatch release) {
        return builder -> builder.type(type).defaultWorker(workerId)
                .initialState(TestState.INIT)
                .step(TestState.INIT, context -> {
                    entered.countDown();
                    awaitLatch(release, "Shutdown test should release worker step");
                }, TestState.DONE)
                .terminal(TestState.DONE)
                .build();
    }

    private int instanceCount() {
        return database.queryForObject("select count(*) from xs_application_instance", rs -> rs.getInt(1))
                .orElseThrow();
    }

    private void awaitWorkerDrain(Thread shutdown) {
        try {
            assertFalse(shutdown.join(Duration.ofMillis(300)),
                    "Engine shutdown should wait for accepted worker task");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private enum TestState implements ProcessState {
        INIT, DONE
    }
}
