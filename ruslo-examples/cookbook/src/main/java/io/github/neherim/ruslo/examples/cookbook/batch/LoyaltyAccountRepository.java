package io.github.neherim.ruslo.examples.cookbook.batch;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** Application table of loyalty accounts ({@code schema.sql}). */
@Repository
public class LoyaltyAccountRepository {
    private final JdbcClient jdbcClient;

    public LoyaltyAccountRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Inserts an account with zero points. */
    public void insert(long id) {
        jdbcClient.sql("insert into loyalty_account (id, points) values (?, 0)")
                .param(id)
                .update();
    }

    /** Returns up to {@code limit} account ids after {@code afterId}, in ascending order. */
    public List<Long> findIdsAfter(long afterId, int limit) {
        return jdbcClient.sql("select id from loyalty_account where id > ? order by id limit ?")
                .params(afterId, limit)
                .query(Long.class)
                .list();
    }

    /** Adds points to the given accounts. */
    public void addPoints(List<Long> ids, int points) {
        jdbcClient.sql("update loyalty_account set points = points + :points where id in (:ids)")
                .param("points", points)
                .param("ids", ids)
                .update();
    }
}
