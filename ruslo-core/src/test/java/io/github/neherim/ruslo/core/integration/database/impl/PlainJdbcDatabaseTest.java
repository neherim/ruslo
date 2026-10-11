package io.github.neherim.ruslo.core.integration.database.impl;

import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlainJdbcDatabaseTest {

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
    void doInTransactionWithResultRollsBackRuntimeException() {
        var database = database();
        database.update("create table tx_test (id int primary key)");

        assertThrows(IllegalStateException.class,
                () -> database.doInTransactionWithResult(() -> {
                    database.update("insert into tx_test (id) values (?)", 1);
                    throw new IllegalStateException("boom");
                }));

        var count = database.queryForObject("select count(*) from tx_test", rs -> rs.getInt(1)).orElseThrow();
        assertEquals(0, count);
    }

    @Test
    void nestedTransactionsShareCommitAndDiscardCallbacksOnRollback() {
        var database = database();
        database.update("create table nested_tx (id int primary key)");
        var callbacks = new java.util.ArrayList<Integer>();
        database.doInTransaction(() -> {
            database.update("insert into nested_tx values (1)");
            database.doInTransaction(() -> {
                database.update("insert into nested_tx values (2)");
                database.doAfterCommit(() -> callbacks.add(2));
            });
            assertTrue(callbacks.isEmpty());
            database.doAfterCommit(() -> callbacks.add(1));
        });
        assertEquals(java.util.List.of(2, 1), callbacks);
        assertThrows(IllegalStateException.class, () -> database.doInTransaction(() -> {
            database.doInTransaction(() -> {
                database.update("insert into nested_tx values (3)");
                database.doAfterCommit(() -> callbacks.add(3));
            });
            throw new IllegalStateException("rollback");
        }));
        assertEquals(java.util.List.of(2, 1), callbacks);
        assertEquals(2, database.queryForObject("select count(*) from nested_tx", rs -> rs.getInt(1)).orElseThrow());
    }

    private PlainJdbcDatabase database() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return new PlainJdbcDatabase(dataSource);
    }
}
