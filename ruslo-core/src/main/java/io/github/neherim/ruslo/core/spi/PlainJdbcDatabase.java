package io.github.neherim.ruslo.core.spi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * {@link Database} on plain JDBC, without an external transaction manager.
 * <p>
 * A transaction is bound to the calling thread: the outermost {@code doInTransaction*} call takes a connection from
 * the data source, and nested calls and SQL operations on the same thread use it. SQL operations called outside a
 * transaction run in their own one. {@link #doAfterCommit(Runnable)} outside a transaction runs the action
 * immediately.
 */
public class PlainJdbcDatabase implements Database {
    private static final Logger log = LoggerFactory.getLogger(PlainJdbcDatabase.class);
    private final DataSource dataSource;
    private final ThreadLocal<Transaction> currentTransaction = new ThreadLocal<>();

    public PlainJdbcDatabase(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /**
     * {@inheritDoc}
     * <p>
     * After-commit actions run after the connection is released. Every action runs even if an earlier one throws;
     * the first exception is rethrown with the others suppressed, although the transaction is already committed.
     *
     * @throws PlainJdbcDatabaseException if a connection cannot be obtained or the commit fails
     */
    @Override
    public <T> T doInTransactionWithResult(Supplier<T> action) {
        if (currentTransaction.get() != null) {
            return action.get();
        }
        var transaction = new Transaction(openConnection(), new ArrayList<>());
        currentTransaction.set(transaction);
        T result;
        try {
            result = action.get();
            log.trace("Committing transaction");
            transaction.connection().commit();
        } catch (SQLException e) {
            var failure = new PlainJdbcDatabaseException("Failed to commit transaction", e);
            rollback(transaction.connection(), failure);
            throw failure;
        } catch (RuntimeException | Error e) {
            rollback(transaction.connection(), e);
            throw e;
        } finally {
            currentTransaction.remove();
            release(transaction.connection());
        }
        runAll(transaction.afterCommitActions());
        return result;
    }

    @Override
    public int update(String sql, Object... params) {
        return doInTransactionWithResult(() -> {
            try (var ps = currentConnection().prepareStatement(sql)) {
                bindParameters(ps, params);
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new PlainJdbcDatabaseException("Error during update", e);
            }
        });
    }

    @Override
    public <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params) {
        return doInTransactionWithResult(() -> {
            try (var ps = currentConnection().prepareStatement(sql)) {
                bindParameters(ps, params);
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (rs.next()) {
                        result.add(rowMapper.mapRow(rs));
                    }
                    return result;
                }
            } catch (SQLException e) {
                throw new PlainJdbcDatabaseException("Error during query", e);
            }
        });
    }

    @Override
    public void batchInsert(String sql, List<Object[]> params) {
        doInTransaction(() -> {
            try (var ps = currentConnection().prepareStatement(sql)) {
                for (Object[] rowParams : params) {
                    bindParameters(ps, rowParams);
                    ps.addBatch();
                }
                ps.executeBatch();
            } catch (SQLException e) {
                throw new PlainJdbcDatabaseException("Error during batch insert", e);
            }
        });
    }

    @Override
    public void doAfterCommit(Runnable action) {
        var transaction = currentTransaction.get();
        if (transaction == null) {
            action.run();
            return;
        }
        transaction.afterCommitActions().add(action);
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    private Connection openConnection() {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            log.trace("Started transaction");
            return connection;
        } catch (SQLException e) {
            var failure = new PlainJdbcDatabaseException("Failed to start transaction", e);
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        }
    }

    private Connection currentConnection() {
        var transaction = currentTransaction.get();
        if (transaction == null) {
            throw new IllegalStateException("No transaction bound to the current thread");
        }
        return transaction.connection();
    }

    private static void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException e) {
            failure.addSuppressed(e);
        }
    }

    /** Never throws: the transaction outcome is already decided, a release failure must not replace it. */
    private static void release(Connection connection) {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            log.warn("Failed to restore auto-commit before closing connection", e);
        }
        try {
            connection.close();
        } catch (SQLException e) {
            log.error("Failed to close connection", e);
        }
    }

    private static void runAll(List<Runnable> actions) {
        RuntimeException failure = null;
        for (var action : actions) {
            try {
                action.run();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static void bindParameters(PreparedStatement statement, Object[] params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            setParameter(statement, i + 1, params[i]);
        }
    }

    private static void setParameter(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof Instant instant) {
            statement.setObject(index, instant.atOffset(ZoneOffset.UTC));
            return;
        }
        statement.setObject(index, value);
    }

    private record Transaction(Connection connection, List<Runnable> afterCommitActions) {
    }
}
