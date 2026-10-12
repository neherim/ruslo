package io.github.neherim.ruslo.spring.config.property;

import io.github.neherim.ruslo.core.api.DatabaseDialect;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "ruslo", ignoreUnknownFields = false)
public class ProcessEngineProperties {
    private DatabaseDialect databaseDialect;
    private DatabaseMigrationProperties databaseMigration = new DatabaseMigrationProperties();
    private DispatcherProperties dispatcher = new DispatcherProperties();
    private LoggingProperties logging = new LoggingProperties();
    private HistoryProperties history = new HistoryProperties();
    private Map<String, WorkerProperties> workers = new HashMap<>();
    private final Map<String, Object> adminUi = new HashMap<>();

    /**
     * Reserves the {@code ruslo.admin-ui} section so that strict binding of engine properties accepts it.
     * Its values are handled by the ruslo-admin-ui module.
     */
    public Map<String, Object> getAdminUi() {
        return adminUi;
    }

    public DispatcherProperties getDispatcher() {
        return dispatcher;
    }

    public DatabaseDialect getDatabaseDialect() {
        return databaseDialect;
    }

    public void setDatabaseDialect(DatabaseDialect databaseDialect) {
        this.databaseDialect = databaseDialect;
    }

    public void setDispatcher(DispatcherProperties dispatcher) {
        this.dispatcher = dispatcher;
    }

    public Map<String, WorkerProperties> getWorkers() {
        return workers;
    }

    public void setWorkers(Map<String, WorkerProperties> workers) {
        this.workers = workers != null ? new HashMap<>(workers) : new HashMap<>();
    }

    public List<WorkerSpec> getWorkerSpecs() {
        validateDefaultWorkerAlias();

        return workers.entrySet()
            .stream()
            .map(entry -> entry.getValue().toWorkerSpec(canonicalWorkerId(entry.getKey())))
            .toList();
    }

    private void validateDefaultWorkerAlias() {
        if (workers.containsKey(WorkerId.DEFAULT.name()) && workers.containsKey("default")) {
            throw new IllegalArgumentException("ruslo.workers must not define both DEFAULT and default");
        }
    }

    private String canonicalWorkerId(String workerId) {
        if ("default".equals(workerId)) {
            return WorkerId.DEFAULT.name();
        }
        return workerId;
    }

    public DatabaseMigrationProperties getDatabaseMigration() {
        return databaseMigration;
    }

    public void setDatabaseMigration(DatabaseMigrationProperties databaseMigration) {
        this.databaseMigration = databaseMigration;
    }

    public LoggingProperties getLogging() {
        return logging;
    }

    public void setLogging(LoggingProperties logging) {
        this.logging = logging;
    }

    public HistoryProperties getHistory() {
        return history;
    }

    public void setHistory(HistoryProperties history) {
        this.history = history;
    }
}
