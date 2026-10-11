package io.github.neherim.ruslo.core.spi;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Database access used by the engine: transactions, SQL and after-commit hooks.
 * <p>
 * Implementation contract:
 * <ul>
 *   <li>Nested {@code doInTransaction*} calls join the current transaction of the calling thread; SQL operations
 *       use its connection. Only the outermost call commits; an exception escaping it rolls back.</li>
 *   <li>An implementation backed by an external transaction manager also joins the application's transaction.</li>
 *   <li>Row locks ({@code FOR UPDATE}, including {@code SKIP LOCKED}) are held until the outermost transaction
 *       ends, not released when a nested call returns.</li>
 * </ul>
 */
public interface Database {
    /** Runs the action in the current transaction or a new one and returns its result. */
    <T> T doInTransactionWithResult(Supplier<T> action);

    /** Runs the action as {@link #doInTransactionWithResult(Supplier)}. */
    default void doInTransaction(Runnable action) {
        doInTransactionWithResult(() -> {
            action.run();
            return null;
        });
    }

    /** Executes an update and returns the number of affected rows. */
    int update(String sql, Object... params);

    /**
     * Executes a batch insert.
     *
     * @param params one parameter array per row
     */
    void batchInsert(String sql, List<Object[]> params);

    /** Returns the mapped row, or empty if there is none. */
    <T> Optional<T> queryForObject(String sql, RowMapper<T> rowMapper, Object... params);

    List<Map<String, Object>> queryForMap(String sql, Object... params);

    <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params);

    /**
     * Registers an action that runs exactly once after the outermost transaction commits; never on rollback or
     * when a nested call returns. The engine calls it only inside a transaction. Outside one, behavior is up to the
     * implementation: {@link PlainJdbcDatabase} runs the action immediately, the Spring implementation requires
     * active transaction synchronization.
     */
    void doAfterCommit(Runnable action);

    DataSource getDataSource();
}
