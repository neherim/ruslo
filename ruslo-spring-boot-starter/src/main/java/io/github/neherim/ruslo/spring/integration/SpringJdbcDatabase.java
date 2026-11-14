package io.github.neherim.ruslo.spring.integration;

import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.RowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@link Database} on {@link JdbcTemplate} that joins Spring-managed transactions.
 */
public class SpringJdbcDatabase implements Database {
    private final TransactionTemplate transactionTemplate;
    private final JdbcTemplate jdbcTemplate;

    public SpringJdbcDatabase(PlatformTransactionManager transactionManager, DataSource dataSource) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public <T> T doInTransactionWithResult(Supplier<T> action) {
        return transactionTemplate.execute(state -> action.get());
    }

    @Override
    public int update(String sql, Object... params) {
        return jdbcTemplate.update(sql, jdbcParameters(params));
    }

    @Override
    public <T> List<T> queryForList(String sql, RowMapper<T> rowMapper, Object... params) {
        return jdbcTemplate.query(sql, (rs, rn) -> rowMapper.mapRow(rs), jdbcParameters(params));
    }

    @Override
    public void batchInsert(String sql, List<Object[]> params) {
        jdbcTemplate.batchUpdate(sql, params.stream().map(SpringJdbcDatabase::jdbcParameters).toList());
    }

    @Override
    public void doAfterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    @Override
    public DataSource getDataSource() {
        return jdbcTemplate.getDataSource();
    }

    private static Object[] jdbcParameters(Object[] params) {
        return Arrays.stream(params)
            .map(value -> value instanceof Instant instant ? instant.atOffset(ZoneOffset.UTC) : value)
            .toArray();
    }
}
