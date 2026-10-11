package io.github.neherim.ruslo.core;

import io.github.neherim.ruslo.core.api.ProcessEngine;
import io.github.neherim.ruslo.core.api.ProcessEngineBuilder;
import io.github.neherim.ruslo.core.api.ProcessEngineEmulator;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.PlainJdbcDatabase;
import io.github.neherim.ruslo.core.management.DatabaseSchemaMigrator;
import org.junit.jupiter.api.BeforeEach;

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
        return new ProcessEngineBuilder(database)
                .addProcessDefinitions(java.util.List.of(definitions))
                .withDispatcherEnabled(false)
                .withDatabaseMigrationEnabled(false)
                .registerWorker(
                        WorkerSpec.builder(WorkerId.DEFAULT)
                                .transactional()
                                .build())
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
