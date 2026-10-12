package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.definition.ErrorResult;
import io.github.neherim.ruslo.core.api.definition.ProcessContext;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessError;
import io.github.neherim.ruslo.core.api.definition.ProcessErrorHandler;
import io.github.neherim.ruslo.core.api.definition.ProcessSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessSpecBuilder;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.runtime.logging.MdcKeys;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProcessMdcTest extends BaseTest {

    @Test
    void stepExecutionWritesConfiguredMdcAndProcessContextCorrelationId() {
        var observedCorrelationId = new AtomicReference<String>();
        var observedMdc = new AtomicReference<Map<String, String>>();
        var processEngine = newProcessEngineWith(new MdcObservingDefinition(observedCorrelationId, observedMdc));
        var processId = processEngine.newProcess(MdcObservingDefinition.TYPE)
            .correlationId("correlation-ctx")
            .start();

        emulator(processEngine).runUntilStop(processId);

        var process = processEngine.processes().byId(processId).singleDetails().orElseThrow();
        assertEquals("correlation-ctx", observedCorrelationId.get());
        assertEquals("correlation-ctx", observedMdc.get().get("correlation_id"));
        assertEquals(processId.toString(), observedMdc.get().get("process_id"));
        assertEquals(MdcObservingDefinition.TYPE.name(), observedMdc.get().get("process_type"));
        assertNull(observedMdc.get().get("process_state"));
        assertNull(MDC.get("correlation_id"));
        assertNull(MDC.get("process_id"));
        assertNull(MDC.get("process_type"));
    }

    @Test
    void processMdcRestoresPreviousAndNestedValues() {
        var processMdc = new ProcessMdc(MdcKeys.defaults());
        var outer = process("outer", MdcObservingDefinition.TYPE);
        var inner = process("inner", ErrorDefinition.TYPE);
        MDC.put("correlation_id", "previous-correlation");
        MDC.put("process_id", "previous-process");
        try (var outerScope = processMdc.open(outer)) {
            assertEquals("outer", MDC.get("correlation_id"));
            try (var innerScope = processMdc.open(inner)) {
                assertEquals("inner", MDC.get("correlation_id"));
                assertEquals(inner.getId().toString(), MDC.get("process_id"));
            }
            assertEquals("outer", MDC.get("correlation_id"));
            assertEquals(outer.getId().toString(), MDC.get("process_id"));
        } finally {
            assertEquals("previous-correlation", MDC.get("correlation_id"));
            assertEquals("previous-process", MDC.get("process_id"));
            MDC.clear();
        }
    }

    @Test
    void customMdcKeyConfigurationWritesOnlyCustomKeys() {
        var configuration = new MdcKeys("cid", "pid", "ptype");
        var processMdc = new ProcessMdc(configuration);
        var process = process("custom-correlation", MdcObservingDefinition.TYPE);

        try (var ignored = processMdc.open(process)) {
            assertEquals("custom-correlation", MDC.get("cid"));
            assertEquals(process.getId().toString(), MDC.get("pid"));
            assertEquals(MdcObservingDefinition.TYPE.name(), MDC.get("ptype"));
            assertNull(MDC.get("correlation_id"));
            assertNull(MDC.get("process_id"));
            assertNull(MDC.get("process_type"));
            assertNull(MDC.get("process_state"));
        }
    }

    @Test
    void stepErrorHandlerRunsUserErrorHandlerInsideProcessMdcScope() {
        var observedMdc = new AtomicReference<Map<String, String>>();
        var definition = new ErrorDefinition(observedMdc);
        var fixture = WorkerTestFixture.with(database, definition);
        var process = fixture.insertNewProcess(ErrorDefinition.TYPE, "error-correlation");

        fixture.stepErrorHandler()
            .handleHandlerError(
                    new IllegalStateException("boom"), fixture.task(process));

        assertEquals("error-correlation", observedMdc.get().get("correlation_id"));
        assertEquals(process.getId().toString(), observedMdc.get().get("process_id"));
        assertEquals(ErrorDefinition.TYPE.name(), observedMdc.get().get("process_type"));
        assertNull(MDC.get("correlation_id"));
    }

    private io.github.neherim.ruslo.core.runtime.process.ProcessInstance process(
            String correlationId,
            ProcessType type
    ) {
        return WorkerTestFixture.with(database,
                new MdcObservingDefinition(new AtomicReference<>(), new AtomicReference<>()),
                new ErrorDefinition(new AtomicReference<>()))
            .newProcess(type, correlationId);
    }

    private record MdcObservingDefinition(
            AtomicReference<String> observedCorrelationId,
            AtomicReference<Map<String, String>> observedMdc
    )
            implements ProcessDefinition {
        private static final ProcessType TYPE = ProcessType.of("MDC_OBSERVING");

        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder.type(TYPE)
                .initialState(State.INIT)
                .step(State.INIT, ctx -> {
                    observedCorrelationId.set(ctx.correlationId());
                    var mdc = new HashMap<String, String>();
                    mdc.put("correlation_id", MDC.get("correlation_id"));
                    mdc.put("process_id", MDC.get("process_id"));
                    mdc.put("process_type", MDC.get("process_type"));
                    mdc.put("process_state", MDC.get("process_state"));
                    observedMdc.set(mdc);
                }, State.DONE)
                .terminal(State.DONE)
                .build();
        }

        private enum State implements ProcessState {
            INIT,
            DONE
        }
    }

    private record ErrorDefinition(AtomicReference<Map<String, String>> observedMdc) implements ProcessDefinition {
        private static final ProcessType TYPE = ProcessType.of("MDC_ERROR");

        @Override
        public ProcessSpec define(ProcessSpecBuilder builder) {
            return builder.type(TYPE)
                .onError(errorHandler(), State.DONE)
                .initialState(State.INIT)
                .step(State.INIT, ctx -> {}, State.DONE)
                .terminal(State.DONE)
                .build();
        }

        private ProcessErrorHandler errorHandler() {
            return (ProcessContext context, ProcessError error) -> {
                observedMdc.set(Map.of(
                        "correlation_id", MDC.get("correlation_id"),
                        "process_id", MDC.get("process_id"),
                        "process_type", MDC.get("process_type")));
                return ErrorResult.fallback();
            };
        }

        private enum State implements ProcessState {
            INIT,
            DONE
        }
    }
}
