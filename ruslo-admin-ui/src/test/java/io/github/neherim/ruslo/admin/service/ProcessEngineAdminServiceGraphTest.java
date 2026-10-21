package io.github.neherim.ruslo.admin.service;

import io.github.neherim.ruslo.admin.dto.DashboardResponse;
import io.github.neherim.ruslo.admin.dto.DefinitionResponse;
import io.github.neherim.ruslo.admin.dto.ProcessGraphResponse.Edge;
import io.github.neherim.ruslo.admin.dto.ProcessGraphResponse.Node;
import io.github.neherim.ruslo.admin.dto.StateHistoryResponse;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.MessageType;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessState;
import io.github.neherim.ruslo.core.api.definition.VariableType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.management.DatabaseSchemaMigrator;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ProcessEngineAdminServiceGraphTest {
    private ProcessEngine engine;

    @AfterEach
    void closeEngine() {
        if (engine != null) engine.close();
    }

    @Test
    void graphShouldUseStateDescriptionsAndLabelTransitions() {
        ProcessDefinition definition = builder -> builder.type("GRAPH")
            .initialState(State.CHECK)
            .step(State.CHECK, context -> Outcome.OK, routes -> routes
                .on(Outcome.OK, State.WAIT)
                .on(Outcome.FAIL, State.DONE))
            .await(State.WAIT, await -> await
                .message(MessageType.of("approve", String.class), (context, message) -> {}, State.DONE)
                .every(Duration.ofSeconds(30), context -> Outcome.FAIL, routes -> routes
                    .on(Outcome.OK, State.DONE)
                    .on(Outcome.FAIL, State.WAIT))
                .after(Duration.ofMinutes(90), State.DONE)
                .at(VariableType.of("dueDate", Instant.class), State.DONE))
            .terminal(State.DONE)
            .build();
        var service = new ProcessEngineAdminService(engine(definition), new ProcessEngineAdmin(engine));

        var graph = service.graph("GRAPH").orElseThrow();

        assertThat(graph.initialState()).isEqualTo("CHECK");
        assertThat(graph.nodes()).containsExactly(
                new Node("CHECK", "Check", "STEP", "DEFAULT"),
                new Node("DONE", "Done", "TERMINAL", null),
                new Node("WAIT", "WAIT", "AWAIT", "DEFAULT"));
        assertThat(graph.edges()).containsExactly(
                new Edge("CHECK", "WAIT", "STEP", "OK"),
                new Edge("CHECK", "DONE", "STEP", "FAIL"),
                new Edge("WAIT", "DONE", "MESSAGE", "approve"),
                new Edge("WAIT", "DONE", "EVERY", "every 30s / OK"),
                new Edge("WAIT", "WAIT", "EVERY", "every 30s / FAIL"),
                new Edge("WAIT", "DONE", "AFTER", "after 1h30m"),
                new Edge("WAIT", "DONE", "AT", "at dueDate"));
        assertThat(service.graph("UNKNOWN")).isEmpty();
        assertThat(service.statesFor("GRAPH", "CHECK"))
            .extracting(DefinitionResponse.StateResponse::name, DefinitionResponse.StateResponse::description)
            .containsExactly(tuple("CHECK", "Check"), tuple("DONE", "Done"), tuple("WAIT", null));
    }

    @Test
    void processDetailsShouldDescribeStateAndMarkUndeclaredTransitionsAsManual() {
        ProcessDefinition definition = builder -> builder.type("HISTORY")
            .initialState(State.CHECK)
            .step(State.CHECK, context -> Outcome.OK, routes -> routes
                .on(Outcome.OK, State.WAIT)
                .on(Outcome.FAIL, State.DONE))
            .await(State.WAIT, await -> await
                .message(MessageType.of("approve", String.class), (context, message) -> {}, State.DONE))
            .terminal(State.DONE)
            .build();
        var service = new ProcessEngineAdminService(engine(definition), new ProcessEngineAdmin(engine));
        var processId = service.startProcess("HISTORY", null, "{}");
        new ProcessEngineEmulator(engine).runOneStep(processId);

        var waiting = service.process(processId).orElseThrow();
        service.changeState(processId, "CHECK");
        var moved = service.process(processId).orElseThrow();

        assertThat(waiting.stateDescription()).isNull();
        assertThat(moved.stateDescription()).isEqualTo("Check");
        assertThat(moved.stateHistory())
            .extracting(StateHistoryResponse::state, StateHistoryResponse::manual)
            .containsExactly(tuple("CHECK", false), tuple("WAIT", false), tuple("CHECK", true));
        assertThat(moved.version()).isNotEqualTo(waiting.version());
    }

    @Test
    void dashboardShouldCountIncompleteProcessesByTypeInBrowserZone() {
        ProcessDefinition definition = builder -> builder.type("COUNTED")
            .initialState(State.CHECK)
            .step(State.CHECK, context -> Outcome.OK, routes -> routes
                .on(Outcome.OK, State.DONE)
                .on(Outcome.FAIL, State.DONE))
            .terminal(State.DONE)
            .build();
        var service = new ProcessEngineAdminService(engine(definition), new ProcessEngineAdmin(engine));
        service.startProcess("COUNTED", null, "{}");
        service.startProcess("COUNTED", null, "{}");
        var zone = ZoneId.of("Asia/Tokyo");

        var dashboard = service.dashboard(zone);

        assertThat(dashboard.activeCount()).isEqualTo(2);
        assertThat(dashboard.failedCount()).isZero();
        assertThat(dashboard.overdueCount()).isZero();
        assertThat(dashboard.countsFor("COUNTED")).isEqualTo(new DashboardResponse.TypeCounts(2, 0, 0));
        assertThat(dashboard.countsFor("UNKNOWN")).isEqualTo(new DashboardResponse.TypeCounts(0, 0, 0));
        assertThat(dashboard.instances()).isEmpty();
        assertThat(dashboard.timeZone()).isEqualTo("Asia/Tokyo");
        var overdueAt = LocalDateTime.parse(dashboard.overdueAt()).atZone(zone).toInstant();
        assertThat(overdueAt).isBetween(Instant.now().minus(Duration.ofMinutes(2)),
                Instant.now().minus(Duration.ofMinutes(1)));
    }

    private ProcessEngine engine(ProcessDefinition definition) {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        DatabaseSchemaMigrator.update(dataSource);
        engine = new ProcessEngineBuilder(new PlainJdbcDatabase(dataSource))
            .withDispatcherEnabled(false)
            .withDatabaseMigrationEnabled(false)
            .registerWorker(WorkerSpec.builder(WorkerId.DEFAULT).transactional().build())
            .addProcessDefinition(definition)
            .build()
            .start();
        return engine;
    }

    private enum Outcome {
        OK,
        FAIL
    }

    private enum State implements ProcessState {
        CHECK("Check"),
        WAIT(null),
        DONE("Done");

        private final String description;

        State(String description) {
            this.description = description;
        }

        @Override
        public String description() {
            return description == null ? name() : description;
        }
    }
}
