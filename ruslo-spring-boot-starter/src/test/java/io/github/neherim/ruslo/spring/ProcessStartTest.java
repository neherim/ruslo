package io.github.neherim.ruslo.spring;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.spring.config.ProcessEngineInitializer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.awaitility.Awaitility.await;

@SpringBootTest(
        classes = ProcessStartTest.TestConfiguration.class,
        properties = "ruslo.dispatcher.enabled=true"
)
public class ProcessStartTest {
    private static final ProcessType TRANSACTIONAL_START_TYPE = ProcessType.of("TRANSACTIONAL_START");
    private static final ProcessType SYNC_MESSAGE_TYPE = ProcessType.of("SYNC_MESSAGE");
    private static final MessageType<String> SYNC_SIGNAL = MessageType.of("SYNC_SIGNAL", String.class);

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private AtomicInteger executions;

    @Autowired
    private AtomicReference<Thread> handlerThread;

    @Test
    void shouldStopProcessEngineOnContextClose() {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(TestConfiguration.class)
            .run();
        var initializer = context.getBean(ProcessEngineInitializer.class);
        Assertions.assertTrue(initializer.isRunning(), "Lifecycle should be running before context close");

        context.close();

        Assertions.assertFalse(initializer.isRunning(), "Lifecycle should be stopped after context close");
    }

    @Test
    void processCreatedInSpringTransactionShouldExecuteThroughSchedulerAfterCommit() {
        var processId = new TransactionTemplate(transactionManager).execute(status -> {
            var id = processEngine.newProcess(TRANSACTIONAL_START_TYPE).start();
            Assertions.assertEquals(0, executions.get());
            return id;
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            var process = processEngine.processes().byId(processId).single().orElseThrow();
            Assertions.assertEquals(ProcessStatus.COMPLETED, process.status());
            Assertions.assertEquals(1, executions.get());
        });
    }

    @Test
    void sendMessageSyncShouldRunHandlerInCallerThreadAndSpringTransaction() {
        var processId = processEngine.newProcess(SYNC_MESSAGE_TYPE).start();
        var transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            processEngine.sendMessageSync(processId, SYNC_SIGNAL, "rolled back");
            Assertions.assertEquals(Thread.currentThread(), handlerThread.get());
            status.setRollbackOnly();
        });
        Assertions.assertEquals("WAIT", processEngine.processes()
            .byId(processId)
            .single()
            .orElseThrow()
            .state()
            .name());

        transaction.executeWithoutResult(status -> processEngine.sendMessageSync(processId, SYNC_SIGNAL, "committed"));
        Assertions.assertEquals(ProcessStatus.COMPLETED,
                processEngine.processes().byId(processId).single().orElseThrow().status());
    }

    @Configuration
    @ComponentScan
    @EnableAutoConfiguration
    static class TestConfiguration {

        @Bean
        public WorkerSpec workerSpec() {
            return WorkerSpec.builder(WorkerId.DEFAULT)
                .build();
        }

        @Bean
        public AtomicInteger executions() {
            return new AtomicInteger();
        }

        @Bean
        public AtomicReference<Thread> handlerThread() {
            return new AtomicReference<>();
        }

        @Bean
        public ProcessDefinition syncMessageDefinition(AtomicReference<Thread> handlerThread) {
            return builder -> builder.type(SYNC_MESSAGE_TYPE)
                .initialState(SyncMessageState.WAIT)
                .await(SyncMessageState.WAIT, await -> await.message(SYNC_SIGNAL,
                        (context, message) -> handlerThread.set(Thread.currentThread()), SyncMessageState.DONE))
                .terminal(SyncMessageState.DONE)
                .build();
        }

        @Bean
        public ProcessDefinition transactionalStartDefinition(AtomicInteger executions) {
            return builder -> builder.type(TRANSACTIONAL_START_TYPE)
                .initialState(TransactionalStartState.INIT)
                .step(TransactionalStartState.INIT, context -> {
                    executions.incrementAndGet();
                }, TransactionalStartState.DONE)
                .terminal(TransactionalStartState.DONE)
                .build();
        }
    }

    enum TransactionalStartState implements ProcessState {
        INIT,
        DONE
    }

    enum SyncMessageState implements ProcessState {
        WAIT,
        DONE
    }

}
