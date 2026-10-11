package io.github.neherim.ruslo.core.internal.database;

import io.github.neherim.ruslo.core.api.DatabaseDialect;
import io.github.neherim.ruslo.core.spi.DatabaseDialectResolver;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseDialectResolverTest {

    @Test
    void shouldResolveSupportedDialect() {
        var connections = new AtomicInteger();
        var dataSource = countingDataSource(h2DataSource(), connections);

        assertEquals(0, connections.get());
        assertSame(DatabaseDialect.H2, DatabaseDialectResolver.resolve(dataSource));
        assertEquals(1, connections.get());
    }

    @Test
    void shouldRejectUnsupportedDatabase() {
        var connections = new AtomicInteger();
        var dataSource = productNamedDataSource("SQLite", connections);

        var failure = assertThrows(IllegalStateException.class, () -> DatabaseDialectResolver.resolve(dataSource));

        assertTrue(failure.getMessage().contains("Unsupported database product 'SQLite'"));
        assertEquals(1, connections.get());
    }

    private static JdbcDataSource h2DataSource() {
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:dialect-" + UUID.randomUUID());
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static DataSource countingDataSource(DataSource delegate, AtomicInteger connections) {
        return proxy(DataSource.class, (proxy, method, args) -> {
            if (method.getName().equals("getConnection")) {
                connections.incrementAndGet();
            }
            return invoke(method, delegate, args);
        });
    }

    private static DataSource productNamedDataSource(String productName, AtomicInteger connections) {
        var delegate = h2DataSource();
        return proxy(DataSource.class, (proxy, method, args) -> {
            if (!method.getName().equals("getConnection")) {
                return invoke(method, delegate, args);
            }
            connections.incrementAndGet();
            var connection = (Connection) invoke(method, delegate, args);
            return proxy(Connection.class, (connectionProxy, connectionMethod, connectionArgs) -> {
                if (!connectionMethod.getName().equals("getMetaData")) {
                    return invoke(connectionMethod, connection, connectionArgs);
                }
                var metadata = (DatabaseMetaData) invoke(connectionMethod, connection, connectionArgs);
                return proxy(DatabaseMetaData.class, (metadataProxy, metadataMethod, metadataArgs) -> {
                    if (metadataMethod.getName().equals("getDatabaseProductName")) {
                        return productName;
                    }
                    return invoke(metadataMethod, metadata, metadataArgs);
                });
            });
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException ex) {
            throw ex.getCause();
        }
    }
}
