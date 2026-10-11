package io.github.neherim.ruslo.examples.cookbook.transactionalstart;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** Application table of orders ({@code schema.sql}). */
@Repository
public class PurchaseOrderRepository {
    private final JdbcClient jdbcClient;

    public PurchaseOrderRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /** Inserts an order. */
    public void insert(String orderId, String item, int quantity, String status) {
        jdbcClient.sql("insert into purchase_order (id, item, quantity, status) values (?, ?, ?, ?)")
                .params(orderId, item, quantity, status)
                .update();
    }

    /** Changes the order status. */
    public void updateStatus(String orderId, String status) {
        jdbcClient.sql("update purchase_order set status = ? where id = ?")
                .params(status, orderId)
                .update();
    }

    /** Returns the order status if the order exists. */
    public Optional<String> findStatus(String orderId) {
        return jdbcClient.sql("select status from purchase_order where id = ?")
                .param(orderId)
                .query(String.class)
                .optional();
    }
}
