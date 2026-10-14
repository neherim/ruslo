package io.github.neherim.ruslo.core.integration.database.impl;

import io.github.neherim.ruslo.core.api.exception.TooManyRowsException;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabaseException;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

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
        var callbacks = new ArrayList<Integer>();
        database.doInTransaction(() -> {
            database.update("insert into nested_tx values (1)");
            database.doInTransaction(() -> {
                database.update("insert into nested_tx values (2)");
                database.doAfterCommit(() -> callbacks.add(2));
            });
            assertTrue(callbacks.isEmpty());
            database.doAfterCommit(() -> callbacks.add(1));
        });
        assertEquals(List.of(2, 1), callbacks);
        assertThrows(IllegalStateException.class, () -> database.doInTransaction(() -> {
            database.doInTransaction(() -> {
                database.update("insert into nested_tx values (3)");
                database.doAfterCommit(() -> callbacks.add(3));
            });
            throw new IllegalStateException("rollback");
        }));
        assertEquals(List.of(2, 1), callbacks);
        assertEquals(2, database.queryForObject("select count(*) from nested_tx", rs -> rs.getInt(1)).orElseThrow());
    }

    @Test
    void failedConnectionReleaseDoesNotLeakTransactionIntoNextCall() {
        var h2 = h2DataSource();
        var database = new PlainJdbcDatabase(failingDataSource(h2,
                call -> call.equals("close") || call.equals("setAutoCommit(true)")));
        database.update("create table release_test (id int primary key)");
        var callbacks = new ArrayList<Integer>();

        database.doInTransaction(() -> {
            database.update("insert into release_test values (1)");
            database.doAfterCommit(() -> callbacks.add(1));
        });
        database.doInTransaction(() -> {
            database.update("insert into release_test values (2)");
            database.doAfterCommit(() -> callbacks.add(2));
        });

        assertEquals(List.of(1, 2), callbacks);
        var committed = new PlainJdbcDatabase(h2).queryForObject("select count(*) from release_test",
                rs -> rs.getInt(1))
            .orElseThrow();
        assertEquals(2, committed);
    }

    @Test
    void failedTransactionStartClosesConnection() {
        var closed = new ArrayList<String>();
        var database = new PlainJdbcDatabase(failingDataSource(h2DataSource(), call -> {
            if (call.equals("close")) {
                closed.add(call);
            }
            return call.equals("setAutoCommit(false)");
        }));

        assertThrows(PlainJdbcDatabaseException.class, () -> database.doInTransaction(() -> {}));

        assertEquals(List.of("close"), closed);
    }

    @Test
    void failingAfterCommitActionDoesNotSkipOtherActions() {
        var database = database();
        var callbacks = new ArrayList<Integer>();
        var first = new IllegalStateException("first");
        var second = new IllegalStateException("second");

        var actual = assertThrows(IllegalStateException.class, () -> database.doInTransaction(() -> {
            database.doAfterCommit(() -> {
                throw first;
            });
            database.doAfterCommit(() -> callbacks.add(1));
            database.doAfterCommit(() -> {
                throw second;
            });
        }));

        assertSame(first, actual);
        assertArrayEquals(new Throwable[]{second}, actual.getSuppressed());
        assertEquals(List.of(1), callbacks);
    }

    @Test
    void queryForObjectRejectsMoreThanOneRow() {
        var database = database();
        database.update("create table many_rows (id int primary key)");
        database.update("insert into many_rows values (1), (2)");

        assertThrows(TooManyRowsException.class,
                () -> database.queryForObject("select id from many_rows", rs -> rs.getInt(1)));
    }

    private PlainJdbcDatabase database() {
        return new PlainJdbcDatabase(h2DataSource());
    }

    private JdbcDataSource h2DataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + getClass().getSimpleName() + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    /**
     * Wraps connections so that {@code close} and {@code setAutoCommit(x)} throw when {@code fails} accepts the call
     * name; the real method still runs first, so the connection stays consistent.
     */
    private static DataSource failingDataSource(DataSource delegate, Predicate<String> fails) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> {
                    var result = invoke(delegate, method, args);
                    if (!method.getName().equals("getConnection")) {
                        return result;
                    }
                    var connection = (Connection) result;
                    return Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                            (connectionProxy, connectionMethod, connectionArgs) -> {
                                var call = connectionMethod.getName().equals("setAutoCommit")
                                        ? "setAutoCommit(" + connectionArgs[0] + ")"
                                        : connectionMethod.getName();
                                var connectionResult = invoke(connection, connectionMethod, connectionArgs);
                                if ((call.equals("close") || call.startsWith("setAutoCommit")) && fails.test(call)) {
                                    throw new SQLException(call + " failed");
                                }
                                return connectionResult;
                            });
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
