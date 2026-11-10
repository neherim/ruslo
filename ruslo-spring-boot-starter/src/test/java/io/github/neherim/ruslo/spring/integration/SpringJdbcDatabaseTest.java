package io.github.neherim.ruslo.spring.integration;

import com.zaxxer.hikari.HikariDataSource;
import io.github.neherim.ruslo.core.api.ProcessEngineAdmin;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.definition.*;
import io.github.neherim.ruslo.core.api.exception.MessageNotExpectedException;
import io.github.neherim.ruslo.core.api.exception.ProcessStatusException;
import io.github.neherim.ruslo.core.api.exception.TooManyRowsException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringJdbcDatabaseTest {
    private static final ProcessType SEARCH_TYPE = ProcessType.of("SEARCH");
    private static final ProcessState SEARCH_START = ProcessState.of("START");
    private static final ProcessState SEARCH_DONE = ProcessState.of("DONE");

    @Test
    void singleDetailsWorksWithoutAnOuterTransactionWithOnePooledConnection() {
        try (var source = new HikariDataSource()) {
            source.setJdbcUrl("jdbc:h2:mem:single-connection-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
            source.setMaximumPoolSize(1);
            source.setMinimumIdle(1);
            source.setConnectionTimeout(500);
            var manager = new DataSourceTransactionManager(source);
            var database = new SpringJdbcDatabase(manager, source);
            ProcessDefinition definition = SpringJdbcDatabaseTest::defineSearchProcess;
            try (
                    var engine = new ProcessEngineBuilder(database).addProcessDefinitions(List.of(definition))
                        .withDispatcherEnabled(false)
                        .build()
                        .start()
            ) {
                var id = engine.newProcess(SEARCH_TYPE).variable("key", "value").start();

                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                var details = engine.processes().byId(id).singleDetails().orElseThrow();

                assertEquals(id, details.id());
                assertEquals(1, details.stateHistory().size());
                assertEquals("value", details.variables().find(VariableType.of("key", String.class)).orElseThrow());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(0, source.getHikariPoolMXBean().getActiveConnections());

                new TransactionTemplate(manager).executeWithoutResult(status -> {
                    var uncommittedId = engine.newProcess(SEARCH_TYPE).start();
                    var uncommitted = engine.processes().byId(uncommittedId).withLock().singleDetails().orElseThrow();
                    assertEquals(uncommittedId, uncommitted.id());
                    assertEquals(1, uncommitted.stateHistory().size());
                    status.setRollbackOnly();
                });
                assertEquals(1, engine.processes().list().size());
            }
        }
    }

    @Test
    void ambiguousSearchDoesNotMarkApplicationTransactionRollbackOnly() {
        var database = database();
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(database.getDataSource()));
        ProcessDefinition definition = SpringJdbcDatabaseTest::defineSearchProcess;
        try (
                var engine = new ProcessEngineBuilder(database).addProcessDefinitions(List.of(definition))
                    .withDispatcherEnabled(false)
                    .build()
                    .start()
        ) {
            database.update("create table application_data (id int primary key)");
            engine.newProcess(SEARCH_TYPE).start();
            engine.newProcess(SEARCH_TYPE).start();
            transaction.executeWithoutResult(status -> {
                database.update("insert into application_data values (1)");
                assertThrows(TooManyRowsException.class, () -> engine.processes().single());
                assertThrows(TooManyRowsException.class, () -> engine.processes().singleDetails());
                assertThrows(TooManyRowsException.class, () -> engine.processes().withLock().single());
                assertThrows(TooManyRowsException.class, () -> engine.processes().withLock().singleDetails());
                assertFalse(status.isRollbackOnly());
            });

            assertEquals(1,
                    database.queryForObject("select count(*) from application_data", rs -> rs.getInt(1)).orElseThrow());
        }
    }

    private static ProcessSpec defineSearchProcess(ProcessSpecBuilder builder) {
        return builder.type(SEARCH_TYPE)
            .initialState(SEARCH_START)
            .step(SEARCH_START, SpringJdbcDatabaseTest::searchStep, SEARCH_DONE)
            .terminal(SEARCH_DONE)
            .build();
    }

    private static void searchStep(ProcessContext context) {
    }

    @Test
    void doInTransactionWithResultRethrowsRuntimeExceptionWithoutWrapping() {
        var database = database();
        var expected = new IllegalStateException("boom");

        var actual = assertThrows(IllegalStateException.class,
                () -> database.doInTransactionWithResult(() -> {
                    throw expected;
                }));

        assertSame(expected, actual);
    }

    @Test
    void engineCommandsJoinApplicationTransactionAndCallbacksWaitForCommit() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:atomic-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var manager = new DataSourceTransactionManager(source);
        var database = new SpringJdbcDatabase(manager, source);
        var transaction = new TransactionTemplate(manager);
        var messageType = MessageType.of("signal", String.class);
        var waiting = ProcessState.of("WAITING");
        var done = ProcessState.of("DONE");
        var type = ProcessType.of("ATOMIC");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(waiting)
            .await(waiting, await -> await.message(messageType, (context, message) -> {}, done))
            .terminal(done)
            .build();
        try (
                var engine = new ProcessEngineBuilder(database).addProcessDefinitions(List.of(definition))
                    .withDispatcherEnabled(false)
                    .build()
                    .start()
        ) {
            database.update("create table application_data (id int primary key)");
            var callbacks = new ArrayList<String>();
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                database.update("insert into application_data values (1)");
                engine.newProcess(type).start();
                database.doInTransaction(() -> database.doAfterCommit(() -> callbacks.add("rollback")));
                assertTrue(callbacks.isEmpty());
                throw new IllegalStateException("rollback");
            }));
            assertEquals(0, new ProcessEngineAdmin(engine).countIncompleteProcesses());
            assertEquals(0,
                    database.queryForObject("select count(*) from application_data", rs -> rs.getInt(1)).orElseThrow());
            assertTrue(callbacks.isEmpty());
            var id = transaction.execute(status -> {
                database.update("insert into application_data values (2)");
                var created = engine.newProcess(type).start();
                database.doInTransaction(() -> database.doAfterCommit(() -> callbacks.add("commit")));
                assertTrue(callbacks.isEmpty());
                return created;
            });
            assertEquals(List.of("commit"), callbacks);
            assertEquals(1, new ProcessEngineAdmin(engine).countIncompleteProcesses());
            assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
                database.update("insert into application_data values (3)");
                engine.sendMessage(id, messageType, "rolled back");
                throw new IllegalStateException("rollback");
            }));
            assertEquals("[]", database.queryForObject("select messages from xs_process_instance where id = ?",
                    rs -> rs.getString(1), id)
                .orElseThrow());
            transaction.executeWithoutResult(status -> {
                database.update("insert into application_data values (4)");
                engine.sendMessage(id, messageType, "committed");
            });
            assertTrue(database.queryForObject("select messages from xs_process_instance where id = ?",
                    rs -> rs.getString(1), id)
                .orElseThrow()
                .contains("committed"));
            assertEquals(2,
                    database.queryForObject("select count(*) from application_data", rs -> rs.getInt(1)).orElseThrow());
        }
    }

    @Test
    void rejectionDoesNotMarkApplicationTransactionRollbackOnly() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:rejecting-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        var manager = new DataSourceTransactionManager(source);
        var database = new SpringJdbcDatabase(manager, source);
        var transaction = new TransactionTemplate(manager);
        var signal = MessageType.of("signal", String.class);
        var other = MessageType.of("other", String.class);
        var waiting = ProcessState.of("WAITING");
        var later = ProcessState.of("LATER");
        var done = ProcessState.of("DONE");
        var type = ProcessType.of("REJECTING");
        ProcessDefinition definition = builder -> builder.type(type)
            .initialState(waiting)
            .await(waiting, await -> await.message(signal, (context, message) -> {}, later))
            .await(later, await -> await.message(other, (context, message) -> {}, done))
            .terminal(done)
            .build();
        try (
                var engine = new ProcessEngineBuilder(database).addProcessDefinitions(List.of(definition))
                    .withDispatcherEnabled(false)
                    .build()
                    .start()
        ) {
            database.update("create table application_data (id int primary key)");
            var waitingId = engine.newProcess(type).start();
            var completedId = engine.newProcess(type).start();
            engine.sendMessageSync(completedId, signal, "later");
            engine.sendMessageSync(completedId, other, "done");

            transaction.executeWithoutResult(status -> {
                database.update("insert into application_data values (1)");
                assertThrows(MessageNotExpectedException.class, () -> engine.sendMessage(completedId, signal, "late"));
                assertThrows(MessageNotExpectedException.class, () -> engine.sendMessage(waitingId, other, "wrong"));
                assertThrows(MessageNotExpectedException.class,
                        () -> engine.sendMessageSync(completedId, signal, "late"));
                assertThrows(MessageNotExpectedException.class,
                        () -> engine.sendMessageSync(waitingId, other, "wrong"));
                assertThrows(ProcessStatusException.class, () -> engine.resumeFailedProcess(waitingId));
                assertFalse(status.isRollbackOnly());
            });

            assertEquals(1,
                    database.queryForObject("select count(*) from application_data", rs -> rs.getInt(1)).orElseThrow());
        }
    }

    private SpringJdbcDatabase database() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return new SpringJdbcDatabase(new DataSourceTransactionManager(dataSource), dataSource);
    }
}
