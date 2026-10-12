package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.runtime.worker.WorkerTestFixture;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ErrorFallbackTest extends BaseTest {
    private static final MessageType<String> SIGNAL = MessageType.of("SIGNAL", String.class);

    @Test
    void fixedWithoutFallbackStateFailsAfterRetries() {
        var runs = new AtomicInteger();
        var info = runFailing(runs, builder -> builder.onError(ProcessErrorHandler.fixed(3, Duration.ZERO)), false);
        assertEquals(ProcessStatus.FAILED, info.status());
        assertEquals("START", info.state().name());
        assertEquals(4, runs.get());
    }

    @Test
    void fixedWithFallbackStateMovesThereAfterRetries() {
        var runs = new AtomicInteger();
        var info = runFailing(runs,
                builder -> builder.onError(ProcessErrorHandler.fixed(3, Duration.ZERO), State.FALLBACK), false);
        assertEquals(ProcessStatus.ACTIVE, info.status());
        assertEquals("FALLBACK", info.state().name());
        assertEquals(4, runs.get());
    }

    @Test
    void noRetryWithFallbackStateMovesThereImmediately() {
        var runs = new AtomicInteger();
        var info = runFailing(runs, builder -> builder.onError(ProcessErrorHandler.noRetry(), State.FALLBACK), false);
        assertEquals("FALLBACK", info.state().name());
        assertEquals(1, runs.get());
    }

    @Test
    void fallbackInsideFallbackStateFailsWithoutLooping() {
        var runs = new AtomicInteger();
        var info = runFailing(runs, builder -> builder.onError(ProcessErrorHandler.noRetry(), State.FALLBACK), true);
        assertEquals(ProcessStatus.FAILED, info.status());
        assertEquals("FALLBACK", info.state().name());
        assertTrue(info.lastError().orElseThrow().contains("compensation failed"));
    }

    @Test
    void messageHandlerErrorMovesToFallbackState() {
        var handled = new AtomicInteger();
        ProcessDefinition definition = builder -> builder.type("FALLBACK_MESSAGE")
            .onError(ProcessErrorHandler.noRetry(), State.FALLBACK)
            .initialState(State.START)
            .step(State.START, context -> {}, State.WAIT)
            .await(State.WAIT, await -> await.message(SIGNAL, (context, message) -> {
                handled.incrementAndGet();
                throw new IllegalStateException("handler failed");
            }, State.DONE))
            .step(State.FALLBACK, context -> {}, State.DONE)
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("FALLBACK_MESSAGE")).start();
            emulator(engine).runOneStep(id);
            engine.sendMessage(id, SIGNAL, "first");
            var info = emulator(engine).runOneStep(id);
            assertEquals("FALLBACK", info.state().name());
            assertEquals(ProcessStatus.ACTIVE, info.status());
            assertEquals(1, handled.get());
        } finally {
            engine.close();
        }
    }

    @Test
    void customHandlerChoosesResultByCauseAndAttemptOnAdministrativePath() {
        var engine = newProcessEngineWith(customDefinition());
        try {
            var id = engine.newProcess(ProcessType.of("CUSTOM_ERRORS")).start();
            var retried = emulator(engine).runOneStep(id);
            assertEquals("START", retried.state().name());
            assertEquals(1, retried.attempt());
            assertEquals("FALLBACK", emulator(engine).runOneStep(id).state().name());
        } finally {
            engine.close();
        }
    }

    @Test
    void customHandlerRunsOnWorkerPath() {
        var definition = customDefinition();
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(ProcessType.of("CUSTOM_ERRORS"), "correlation");
        fixture.transactionalWorker().tryExecute(fixture.task(process));
        var stored = fixture.dao().findById(process.getId()).orElseThrow();
        assertEquals(1, stored.getAttempt());
        assertEquals(ProcessStatus.ACTIVE, stored.getStatus());
    }

    @Test
    void runUntilStopContinuesAfterErrorWhenRetryAndFallbackLeadToStep() {
        var engine = newProcessEngineWith(customDefinition());
        try {
            var id = engine.newProcess(ProcessType.of("CUSTOM_ERRORS")).start();
            var info = emulator(engine).runUntilStop(id);
            assertEquals(ProcessStatus.COMPLETED, info.status());
            assertEquals("DONE", info.state().name());
        } finally {
            engine.close();
        }
    }

    @Test
    void runUntilStopStopsOnRetryWithDelay() {
        ProcessDefinition definition = builder -> builder.type("DELAYED_RETRY")
            .onError(ProcessErrorHandler.fixed(1, Duration.ofMinutes(1)))
            .initialState(State.START)
            .step(State.START, context -> {
                throw new IllegalStateException("boom");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("DELAYED_RETRY")).start();
            var info = emulator(engine).runUntilStop(id);
            assertEquals(ProcessStatus.ACTIVE, info.status());
            assertEquals(1, info.attempt());
        } finally {
            engine.close();
        }
    }

    /** Retries the first error, falls back on the second; an {@code IllegalArgumentException} fails the process. */
    private ProcessDefinition customDefinition() {
        ProcessErrorHandler handler = (context, error) -> {
            if (error.cause() instanceof IllegalArgumentException) return ErrorResult.fail();
            return error.attempt() < 1 ? ErrorResult.retryAfter(Duration.ZERO) : ErrorResult.fallback();
        };
        return builder -> builder.type("CUSTOM_ERRORS")
            .onError(handler, State.FALLBACK)
            .initialState(State.START)
            .step(State.START, context -> {
                throw new IllegalStateException("boom");
            }, State.DONE)
            .step(State.FALLBACK, context -> {}, State.DONE)
            .terminal(State.DONE)
            .build();
    }

    /** Runs a process with a failing START (and a failing FALLBACK if {@code failFallback}) until it stops. */
    private io.github.neherim.ruslo.core.api.view.ProcessInstanceDetails runFailing(
            AtomicInteger runs,
            java.util.function.UnaryOperator<ProcessSpecBuilder.TypedBuilder> settings,
            boolean failFallback
    ) {
        ProcessDefinition definition = builder -> settings.apply(builder.type("FALLBACK_STEP"))
            .initialState(State.START)
            .step(State.START, context -> {
                runs.incrementAndGet();
                throw new IllegalStateException("step failed");
            }, State.DONE)
            .step(State.FALLBACK, context -> {
                if (failFallback) throw new IllegalStateException("compensation failed");
            }, State.DONE)
            .terminal(State.DONE)
            .build();
        var engine = newProcessEngineWith(definition);
        try {
            var id = engine.newProcess(ProcessType.of("FALLBACK_STEP")).start();
            var emulator = emulator(engine);
            for (var i = 0; i < 10 && emulator.runOneStep(id).status() == ProcessStatus.ACTIVE; i++) {
                if (engine.processes().byId(id).singleDetails().orElseThrow().state().name().equals("FALLBACK")
                        && !failFallback)
                    break;
            }
            return engine.processes().byId(id).singleDetails().orElseThrow();
        } finally {
            engine.close();
        }
    }

    private enum State implements ProcessState {
        START,
        WAIT,
        FALLBACK,
        DONE
    }
}
