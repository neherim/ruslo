package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.management.DatabaseSchemaMigrator;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import org.junit.jupiter.api.BeforeEach;

import java.time.Clock;

public abstract class BaseTest {
    protected Database database;

    @BeforeEach
    void beforeEach() {
        database = new PlainJdbcDatabase(TestDatabases.newDataSource());
        DatabaseSchemaMigrator.update(database.getDataSource());
    }

    public ProcessEngine getDefaultProcessEngine() {
        return newProcessEngine();
    }

    protected ProcessEngine newProcessEngine() {
        return newProcessEngineWith();
    }

    protected ProcessEngine newProcessEngineWith(ProcessDefinition... definitions) {
        return newProcessEngineWith(Clock.systemDefaultZone(), definitions);
    }

    protected ProcessEngine newProcessEngineWith(Clock clock, ProcessDefinition... definitions) {
        return new ProcessEngineBuilder(database)
            .addProcessDefinitions(java.util.List.of(definitions))
            .withClock(clock)
            .withDispatcherEnabled(false)
            .withDatabaseMigrationEnabled(false)
            .build()
            .start();
    }

    protected ProcessEngineEmulator emulator(ProcessEngine processEngine) {
        return new ProcessEngineEmulator(processEngine);
    }

    protected ProcessEngineEmulator emulator(ProcessEngine processEngine, int maxSteps) {
        return new ProcessEngineEmulator(processEngine, maxSteps);
    }
}
