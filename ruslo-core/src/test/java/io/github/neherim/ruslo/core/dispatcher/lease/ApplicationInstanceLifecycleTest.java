package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.BaseTest;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.view.ApplicationInstanceInfo;
import io.github.neherim.ruslo.core.definitions.SimpleProcessDefinition;
import io.github.neherim.ruslo.core.metrics.InMemoryProcessEngineMetrics;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import io.github.neherim.ruslo.core.utils.UUIDGenerator;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationInstanceLifecycleTest extends BaseTest {

    @Test
    void engineShouldRegisterOnlyWhenDispatcherStartsAndCleanUpOnShutdown() {
        var disabled = new ProcessEngineBuilder(database)
                .withDatabaseMigrationEnabled(false)
                .withDispatcherEnabled(false)
                .build().start();
        assertEquals(0, instanceCount());
        disabled.close();

        var enabled = new ProcessEngineBuilder(database)
                .withDatabaseMigrationEnabled(false)
                .withInstanceHeartbeatInterval(Duration.ofMillis(50))
                .withInstanceTimeout(Duration.ofMillis(200))
                .build().start();
        assertEquals(1, instanceCount());

        enabled.close();
        assertEquals(0, instanceCount());
    }

    @Test
    void instancesShouldReportLeasesAndMarkOwnRegistration() {
        var engine = newProcessEngineWith(new SimpleProcessDefinition());
        var dao = new ApplicationInstanceDao(database);
        var lifecycle = new ApplicationInstanceLifecycle(Duration.ofMinutes(1), Duration.ofMinutes(4), dao,
                new InMemoryProcessEngineMetrics());
        lifecycle.start();
        try {
            var otherId = UUIDGenerator.generate();
            dao.register(otherId, Duration.ofMinutes(4));
            var processId = engine.newProcess(SimpleProcessDefinition.TYPE).start();
            database.update("update xs_process_instance set lease_owner = ? where id = ?", otherId, processId);

            var instances = lifecycle.instances();

            assertEquals(2, instances.size());
            var own = instances.stream().filter(ApplicationInstanceInfo::current).findFirst().orElseThrow();
            var other = instances.stream().filter(info -> !info.current()).findFirst().orElseThrow();
            assertEquals(lifecycle.ownerId(), own.id());
            assertEquals(0, own.leaseCount());
            assertEquals(otherId, other.id());
            assertEquals(1, other.leaseCount());
            assertTrue(other.expiresIn().compareTo(Duration.ofMinutes(3)) > 0);
            assertTrue(other.expiresIn().compareTo(Duration.ofMinutes(4)) <= 0);
        } finally {
            lifecycle.close();
            engine.close();
        }
    }

    @Test
    void removedOwnRowShouldReregisterUnderNewId() {
        var metrics = new InMemoryProcessEngineMetrics();
        var lifecycle = new ApplicationInstanceLifecycle(Duration.ofMillis(10), Duration.ofMillis(40),
                new ApplicationInstanceDao(database), metrics);
        lifecycle.start();
        try {
            var lostId = lifecycle.ownerId();

            database.update("delete from xs_application_instance where id = ?", lostId);

            await().atMost(Duration.ofSeconds(2)).until(() -> !lostId.equals(lifecycle.ownerId()));
            await().atMost(Duration.ofSeconds(2)).until(() -> isRegistered(lifecycle.ownerId()));
            assertFalse(isRegistered(lostId));
        } finally {
            lifecycle.close();
        }
        assertEquals(0, instanceCount());
    }

    @Test
    void restartShouldUseNewInstanceId() {
        var lifecycle = new ApplicationInstanceLifecycle(Duration.ofMinutes(1), Duration.ofMinutes(4),
                new ApplicationInstanceDao(database), new InMemoryProcessEngineMetrics());
        lifecycle.start();
        var firstId = lifecycle.ownerId();
        lifecycle.close();
        lifecycle.start();
        try {
            assertNotEquals(firstId, lifecycle.ownerId());
            assertTrue(isRegistered(lifecycle.ownerId()));
        } finally {
            lifecycle.close();
        }
    }

    @Test
    void errorInHeartbeatShouldNotStopLifecycle() {
        var heartbeats = new AtomicInteger();
        var failingDatabase = new PlainJdbcDatabase(database.getDataSource()) {
            @Override
            public int update(String sql, Object... params) {
                if (sql.contains("set expires_at") && heartbeats.incrementAndGet() == 1) {
                    throw new AssertionError("heartbeat failure");
                }
                return super.update(sql, params);
            }
        };
        var metrics = new InMemoryProcessEngineMetrics();
        var lifecycle = new ApplicationInstanceLifecycle(Duration.ofMillis(10), Duration.ofMinutes(1),
                new ApplicationInstanceDao(failingDatabase), metrics);
        lifecycle.start();
        try {
            await().atMost(Duration.ofSeconds(2)).until(() -> heartbeats.get() >= 3);
            assertEquals(1, metrics.getSystemErrorCount());
        } finally {
            lifecycle.close();
        }
    }

    private boolean isRegistered(java.util.UUID instanceId) {
        return database.queryForObject("select count(*) from xs_application_instance where id = ?",
                rs -> rs.getInt(1), instanceId).orElseThrow() == 1;
    }

    private int instanceCount() {
        return database.queryForObject("select count(*) from xs_application_instance", rs -> rs.getInt(1))
                .orElseThrow();
    }
}
