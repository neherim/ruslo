package io.github.neherim.ruslo.core.runtime.process;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.test.MutableClock;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessInstanceWorkerRoutingTest extends BaseTest {
    private static final ProcessType TYPE = ProcessType.of("ROUTING");
    private static final WorkerId ACTION_WORKER = new WorkerId("ACTION");
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);

    private final MutableClock clock = new MutableClock();

    @Test
    void stateEntryChangesRouteScheduleAndQueueAtomically() {
        var factory = factory();
        var dao = new ProcessInstanceDao(database, Clock.systemUTC(), factory, true,
                new io.github.neherim.ruslo.core.spi.ProcessEngineMetrics() {
                });
        var now = Instant.parse("2026-09-24T10:00:00Z");
        clock.setTime(now);
        var process = factory.newInstance(TYPE, now, null, "routing", Map.of(), Map.of());
        assertEquals(ACTION_WORKER, process.getWorkerId().orElseThrow());
        assertEquals(now, process.getNextRunAt().orElseThrow());
        dao.insert(process);

        process.enterState(State.WAIT);
        assertEquals(WorkerId.DEFAULT, process.getWorkerId().orElseThrow());
        assertEquals(now.plusSeconds(2), process.getAfterDueAt().orElseThrow());
        assertTrue(process.getEveryDueAt().isEmpty());
        process.appendMessage(process.expectedMessageEvent(SIGNAL).orElseThrow(), "payload",
                java.util.UUID.randomUUID(), now.plusSeconds(1));
        assertEquals(now.plusSeconds(1), process.getNextRunAt().orElseThrow());
        dao.update(process);
        var restored = dao.findById(process.getId()).orElseThrow();
        assertEquals("payload",
                restored.getMessages().convert(restored.getMessages().peekFirst().orElseThrow(), SIGNAL).payload());

        clock.setTime(now.plusSeconds(3));
        restored.enterState(State.WAIT);
        assertTrue(restored.getMessages().isEmpty());
        assertEquals(now.plusSeconds(5), restored.getAfterDueAt().orElseThrow());
        clock.setTime(now.plusSeconds(4));
        restored.enterState(State.DONE);
        assertEquals(ProcessStatus.COMPLETED, restored.getStatus());
        assertTrue(restored.getWorkerId().isEmpty());
        assertTrue(restored.getNextRunAt().isEmpty());
        assertTrue(restored.getAfterDueAt().isEmpty());
        dao.update(restored);
        assertEquals(ProcessStatus.COMPLETED, dao.findById(process.getId()).orElseThrow().getStatus());
    }

    private ProcessInstanceFactory factory() {
        ProcessDefinition definition = builder -> builder.type(TYPE)
            .defaultWorker(ACTION_WORKER)
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL,
                    (context, message) -> {}, State.WAIT)
                .after(Duration.ofSeconds(2), State.DONE))
            .terminal(State.DONE)
            .build();
        return new ProcessInstanceFactory(clock, JsonUtils.DEFAULT_OBJECT_MAPPER,
                new ProcessDefinitionRegistry(List.of(definition)));
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        DONE
    }
}
