package io.github.neherim.ruslo.perf.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.management.DatabaseSchemaMigrator;
import io.github.neherim.ruslo.core.metrics.InMemoryProcessEngineMetrics;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Shared PostgreSQL, pool and engine infrastructure for benchmarks. */
public final class BenchmarkEnvironment implements AutoCloseable {
    private final PostgreSQLContainer container;
    private final List<HikariDataSource> pools = new ArrayList<>();
    private final Map<ProcessEngine, HikariDataSource> enginePools = new IdentityHashMap<>();
    private final Database setupDatabase;

    /** Creates the admin pool and applies migrations in the running container. */
    public BenchmarkEnvironment(PostgreSQLContainer container) {
        this.container = container;
        setupDatabase = new PlainJdbcDatabase(createPool("benchmark-setup", 2));
        DatabaseSchemaMigrator.update(setupDatabase.getDataSource());
        var durable = setupDatabase.queryForObject("""
                select current_setting('fsync') = 'on'
                   and current_setting('synchronous_commit') = 'on'
                   and current_setting('full_page_writes') = 'on'
                """, rs -> rs.getBoolean(1)).orElseThrow();
        if (!durable) throw new IllegalStateException("Benchmark requires durable PostgreSQL settings");
    }

    private HikariDataSource createPool(String name, int size) {
        var config = new HikariConfig();
        config.setJdbcUrl(container.getJdbcUrl());
        config.setUsername(container.getUsername());
        config.setPassword(container.getPassword());
        config.setPoolName(name);
        config.setMaximumPoolSize(size);
        config.setMinimumIdle(1);
        var pool = new HikariDataSource(config);
        pools.add(pool);
        return pool;
    }

    /** Builds an engine with its own pool and a fixed 10 ms polling interval. */
    public ProcessEngine createEngine(
            String name,
            int poolSize,
            int concurrency,
            boolean dispatcherEnabled,
            InMemoryProcessEngineMetrics metrics,
            List<? extends ProcessDefinition> definitions,
            Consumer<ProcessEngineBuilder> customizer
    ) {
        var pool = createPool(name, poolSize);
        var builder = new ProcessEngineBuilder(new PlainJdbcDatabase(pool))
            .addProcessDefinitions(definitions)
            .withDatabaseMigrationEnabled(false)
            .withDispatcherEnabled(dispatcherEnabled)
            .withMinProcessDispatchInterval(Duration.ofMillis(10))
            .withMaxProcessDispatchInterval(Duration.ofMillis(10))
            .withMetrics(metrics)
            .registerWorker(WorkerSpec.builder(WorkerId.DEFAULT)
                .transactional()
                .maxConcurrency(concurrency)
                .build());
        customizer.accept(builder);
        var engine = builder.build();
        enginePools.put(engine, pool);
        return engine;
    }

    /** Deletes the previous iteration's data and keeps the schema. */
    public void truncateEngineTables() {
        setupDatabase.update("truncate table xs_process_state_hist, xs_process_instance, "
                + "xs_application_instance restart identity cascade");
    }

    /** Stops the engine and closes its pool. */
    public void stopEngine(ProcessEngine engine) {
        try {
            engine.close();
        } finally {
            var pool = enginePools.remove(engine);
            if (pool != null) {
                pool.close();
                pools.remove(pool);
            }
        }
    }

    public Database database() {
        return setupDatabase;
    }

    /** Returns database settings and the container's actual limits. */
    public Map<String, Object> postgresMetadata() {
        var host = container.getDockerClient()
            .inspectContainerCmd(container.getContainerId())
            .exec()
            .getHostConfig();
        var result = new java.util.LinkedHashMap<String, Object>();
        var docker = container.getDockerClient().infoCmd().exec();
        result.put("dockerCpuCount", docker.getNCPU());
        result.put("dockerMemoryBytes", docker.getMemTotal());
        result.put("image", BenchmarkPostgres.IMAGE);
        result.put("version", setupDatabase.queryForObject("select version()", rs -> rs.getString(1)).orElseThrow());
        result.put("containerMemoryLimitBytes", host.getMemory());
        result.put("containerNanoCpus", host.getNanoCPUs());
        result.put("containerCpuQuota", host.getCpuQuota());
        result.put("containerCpuPeriod", host.getCpuPeriod());
        result.put("containerCpuset", host.getCpusetCpus());
        result.put("settings", setupDatabase.queryForList("""
                select name, setting, unit from pg_settings
                where name in ('shared_buffers', 'max_connections', 'fsync', 'synchronous_commit',
                               'full_page_writes', 'max_wal_size', 'checkpoint_timeout')
                order by name
                """, rs -> Map.of("name", rs.getString(1), "setting", rs.getString(2),
                "unit", rs.getString(3) == null ? "" : rs.getString(3))));
        return result;
    }

    @Override
    public void close() {
        RuntimeException failure = null;
        for (var engine : List.copyOf(enginePools.keySet())) {
            try {
                stopEngine(engine);
            } catch (RuntimeException ex) {
                if (failure == null) failure = ex;
                else
                    failure.addSuppressed(ex);
            }
        }
        pools.forEach(HikariDataSource::close);
        pools.clear();
        if (failure != null) throw failure;
    }
}
