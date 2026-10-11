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
import java.util.*;
import java.util.function.Supplier;

/**
 * {@link Database} on plain JDBC, without an external transaction manager.
 */
public class PlainJdbcDatabase implements Database {
    private final Logger log = LoggerFactory.getLogger(PlainJdbcDatabase.class);
    private final DataSource dataSource;
    private final ThreadLocal<Connection> connectionHolder = new ThreadLocal<>();
    private final ThreadLocal<List<Runnable>> afterCommitActions = new ThreadLocal<>();

    public PlainJdbcDatabase(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public <T> T doInTransactionWithResult(Supplier<T> action) {
        Connection connection = null;
        var isNewTransaction = connectionHolder.get() == null;
        var commited = false;
        try {
            connection = getOrCreateConnection();

            T result = action.get();

            if (isNewTransaction) {
                log.trace("Committing transaction");
                connection.commit();
                commited = true;
            }
            return result;
        } catch (SQLException e) {
            rollbackIfNeeded(connection, isNewTransaction, e);
            throw new PlainJdbcDatabaseException("Transaction failed", e);
        } catch (RuntimeException | Error ex) {
            rollbackIfNeeded(connection, isNewTransaction, ex);
            throw ex;
        } finally {
            if (isNewTransaction && connection != null) {
                try {
                    connection.setAutoCommit(true);
                    connection.close();
                    connectionHolder.remove();
                    if (commited) {
                        runAfterCommitActions();
                    } else {
                        afterCommitActions.remove();
                    }
                } catch (SQLException e) {
                    log.error("Error while closing connection", e);
                }
            }
        }
    }

    private void rollbackIfNeeded(Connection connection, boolean isNewTransaction, Throwable failure) {
        if (!isNewTransaction || connection == null) {
            return;
        }
        try {
            connection.rollback();
        } catch (SQLException ex) {
            failure.addSuppressed(ex);
        }
    }

    private void runAfterCommitActions() {
        var actions = afterCommitActions.get();
        if (actions != null) {
            afterCommitActions.remove();
            actions.forEach(Runnable::run);
        }
    }

    @Override
    public int update(String sql, Object... params) {
        return doInTransactionWithResult(() -> {
            Connection conn = getOrCreateConnection();
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    setParameter(ps, i + 1, params[i]);
                }
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new PlainJdbcDatabaseException("Error during update", e);
            }
        });
    }

    @Override
    public <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params) {
        return doInTransactionWithResult(() -> {
            Connection conn = getOrCreateConnection();
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    setParameter(ps, i + 1, params[i]);
                }
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
            Connection conn = getOrCreateConnection();
            try (var ps = conn.prepareStatement(sql)) {
                for (Object[] rowParams : params) {
                    for (int i = 0; i < rowParams.length; i++) {
                        setParameter(ps, i + 1, rowParams[i]);
                    }
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
        if (connectionHolder.get() == null) {
            action.run();
            return;
        }

        var actions = afterCommitActions.get();
        if (actions == null) {
            actions = new ArrayList<>();
            afterCommitActions.set(actions);
        }
        actions.add(action);
    }

    @Override
    public DataSource getDataSource() {
        return dataSource;
    }

    @Override
    public <T> Optional<T> queryForObject(String sql, RowMapper<T> rowMapper, Object... params) {
        var result = queryForList(sql, rowMapper, params);
        if (result.isEmpty()) {
            return Optional.empty();
        } else {
            return Optional.ofNullable(result.get(0));
        }
    }

    @Override
    public List<Map<String, Object>> queryForMap(String sql, Object... params) {
        var result = new ArrayList<Map<String, Object>>();
        return doInTransactionWithResult(() -> {
            Connection conn = getOrCreateConnection();
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    setParameter(ps, i + 1, params[i]);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    var columnCount = rs.getMetaData().getColumnCount();
                    while (rs.next()) {
                        var row = new HashMap<String, Object>();
                        for (int i = 1; i <= columnCount; i++) {
                            row.put(rs.getMetaData().getColumnName(i), rs.getObject(i));
                        }
                        result.add(row);
                    }
                    return result;
                }
            } catch (SQLException e) {
                throw new PlainJdbcDatabaseException("Error during query", e);
            }
        });
    }

    private Connection getOrCreateConnection() {
        try {
            Connection connection = connectionHolder.get();
            if (connection == null || connection.isClosed()) {
                connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                connectionHolder.set(connection);
                log.trace("Created new connection to database");
            }
            return connection;
        } catch (SQLException e) {
            throw new PlainJdbcDatabaseException(e);
        }
    }

    private static void setParameter(PreparedStatement statement, int index, Object value) throws SQLException {
        if (value instanceof Instant instant) {
            statement.setObject(index, instant.atOffset(ZoneOffset.UTC));
            return;
        }
        statement.setObject(index, value);
    }
}
