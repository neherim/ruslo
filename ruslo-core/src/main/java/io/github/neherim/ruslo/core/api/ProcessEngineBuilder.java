package io.github.neherim.ruslo.core.api;

import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.view.ProcessStatus;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchScheduler;
import io.github.neherim.ruslo.core.dispatcher.ProcessDispatchSignal;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceDao;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceLifecycle;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseDao;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseDispatcher;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseReleaser;
import io.github.neherim.ruslo.core.management.ManagementService;
import io.github.neherim.ruslo.core.metrics.FailSafeProcessEngineMetrics;
import io.github.neherim.ruslo.core.runtime.ProcessRuntimeService;
import io.github.neherim.ruslo.core.runtime.logging.MdcKeys;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceFactory;
import io.github.neherim.ruslo.core.runtime.worker.StepErrorHandler;
import io.github.neherim.ruslo.core.runtime.worker.StepExecutor;
import io.github.neherim.ruslo.core.runtime.worker.WorkerFactory;
import io.github.neherim.ruslo.core.runtime.worker.WorkerRegistry;
import io.github.neherim.ruslo.core.search.ProcessSearchService;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.DatabaseDialectResolver;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.*;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Configures and builds a {@link ProcessEngine}. A transactional {@link WorkerId#DEFAULT} worker is required.
 */
public class ProcessEngineBuilder {
    private static final Duration DEFAULT_INSTANCE_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final Duration DEFAULT_INSTANCE_TIMEOUT = Duration.ofSeconds(60);
    private final Database database;
    private final Map<WorkerId, WorkerSpec> workerSpecs = new HashMap<>();
    private final List<ProcessDefinition> processDefinitions = new ArrayList<>();
    private Clock clock = Clock.systemDefaultZone();
    private ObjectMapper objectMapper = JsonUtils.DEFAULT_OBJECT_MAPPER;
    private boolean databaseMigrationEnabled = true;
    private boolean dispatcherEnabled = true;
    private Duration maxProcessDispatchInterval = Duration.ofSeconds(10);
    private Duration minProcessDispatchInterval = Duration.ofMillis(100);
    private Duration instanceHeartbeatInterval = DEFAULT_INSTANCE_HEARTBEAT_INTERVAL;
    private Duration instanceTimeout = DEFAULT_INSTANCE_TIMEOUT;
    private DatabaseDialect databaseDialect;
    private ProcessEngineMetrics metrics = new ProcessEngineMetrics() {
    };
    private boolean saveProcessHistory = true;
    private String correlationIdMdcKey;
    private String processIdMdcKey;
    private String processTypeMdcKey;

    public ProcessEngineBuilder(Database database) {
        this.database = Objects.requireNonNull(database, "Database must not be null");
        registerDefaultWorker();
    }

    private void registerDefaultWorker() {
        registerWorker(WorkerSpec.builder(WorkerId.DEFAULT)
            .transactional()
            .build());
    }

    /**
     * Registers a worker, replacing one with the same id.
     *
     * @throws IllegalArgumentException if {@link WorkerId#DEFAULT} is not transactional
     */
    public ProcessEngineBuilder registerWorker(WorkerSpec spec) {
        Objects.requireNonNull(spec, "Worker spec cannot be null");
        if (WorkerId.DEFAULT.equals(spec.workerId())
                && spec.executionMode() != WorkerSpec.ExecutionMode.TRANSACTIONAL) {
            throw new IllegalArgumentException("DEFAULT worker must be transactional");
        }
        workerSpecs.put(spec.workerId(), spec);
        return this;
    }

    /** Adds a definition registered and validated by {@link #build()}. */
    public ProcessEngineBuilder addProcessDefinition(ProcessDefinition processDefinition) {
        processDefinitions.add(Objects.requireNonNull(processDefinition, "ProcessDefinition must not be null"));
        return this;
    }

    /** Adds definitions registered and validated by {@link #build()}. */
    public ProcessEngineBuilder addProcessDefinitions(Collection<? extends ProcessDefinition> processDefinitions) {
        Objects.requireNonNull(processDefinitions, "ProcessDefinitions must not be null")
            .forEach(this::addProcessDefinition);
        return this;
    }

    /** Sets the engine clock used for timers, retries and timestamps; system clock by default. */
    public ProcessEngineBuilder withClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "Clock must not be null");
        return this;
    }

    /** Sets the mapper for variables and message payloads. */
    public ProcessEngineBuilder withJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper must not be null");
        return this;
    }

    /** Sets the polling interval when there is no work; 10 s by default. */
    public ProcessEngineBuilder withMaxProcessDispatchInterval(Duration maxDispatchInterval) {
        this.maxProcessDispatchInterval = maxDispatchInterval;
        return this;
    }

    /** Sets the polling interval when there is work; 100 ms by default. */
    public ProcessEngineBuilder withMinProcessDispatchInterval(Duration minDispatchInterval) {
        this.minProcessDispatchInterval = minDispatchInterval;
        return this;
    }

    /** Sets the instance heartbeat interval; 15 s by default. */
    public ProcessEngineBuilder withInstanceHeartbeatInterval(Duration interval) {
        this.instanceHeartbeatInterval = requirePositive(interval, "Instance heartbeat interval");
        return this;
    }

    /**
     * Sets how long an instance without heartbeats stays registered before its leases are released; 60 s by default.
     * Must be at least four heartbeat intervals.
     */
    public ProcessEngineBuilder withInstanceTimeout(Duration timeout) {
        this.instanceTimeout = requirePositive(timeout, "Instance timeout");
        return this;
    }

    /** Sets the SQL dialect; by default {@link #build()} detects it from JDBC metadata. */
    public ProcessEngineBuilder withDatabaseDialect(DatabaseDialect databaseDialect) {
        this.databaseDialect = databaseDialect;
        return this;
    }

    /** Enables schema migration in {@link ProcessEngine#start()}; enabled by default. */
    public ProcessEngineBuilder withDatabaseMigrationEnabled(boolean databaseMigrationEnabled) {
        this.databaseMigrationEnabled = databaseMigrationEnabled;
        return this;
    }

    /**
     * Enables the dispatcher (instance registration and polling) in {@link ProcessEngine#start()}; enabled by
     * default. Without it, this instance does not execute processes in the background.
     */
    public ProcessEngineBuilder withDispatcherEnabled(boolean dispatcherEnabled) {
        this.dispatcherEnabled = dispatcherEnabled;
        return this;
    }

    /** Sets the metrics sink; no-op by default. */
    public ProcessEngineBuilder withMetrics(ProcessEngineMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "Metrics must not be null");
        return this;
    }

    /**
     * Sets the MDC key for the correlation id; {@code correlation_id} by default, also for a blank key.
     * The engine also reads the caller's correlation id from this key when starting a process.
     */
    public ProcessEngineBuilder withCorrelationIdMdcKey(String key) {
        this.correlationIdMdcKey = key;
        return this;
    }

    /** Sets the MDC key for the process id; {@code process_id} by default, also for a blank key. */
    public ProcessEngineBuilder withProcessIdMdcKey(String key) {
        this.processIdMdcKey = key;
        return this;
    }

    /** Sets the MDC key for the process type; {@code process_type} by default, also for a blank key. */
    public ProcessEngineBuilder withProcessTypeMdcKey(String key) {
        this.processTypeMdcKey = key;
        return this;
    }

    /** Enables writing state history to {@code xs_process_state_hist}; enabled by default. */
    public ProcessEngineBuilder withHistoryEnabled(boolean historyEnabled) {
        this.saveProcessHistory = historyEnabled;
        return this;
    }

    /**
     * Builds the engine; call {@link ProcessEngine#start()} to start it.
     *
     * @throws IllegalArgumentException if a definition is invalid, the transactional {@link WorkerId#DEFAULT}
     *                                  worker is missing or the instance timeout is too short
     */
    public ProcessEngine build() {
        if (instanceTimeout.compareTo(instanceHeartbeatInterval.multipliedBy(4)) < 0) {
            throw new IllegalArgumentException("Instance timeout must be at least four times heartbeat interval");
        }
        var effectiveMetrics = FailSafeProcessEngineMetrics.wrap(metrics);
        var effectiveDatabaseDialect = databaseDialect != null
                ? databaseDialect
                : DatabaseDialectResolver.resolve(database.getDataSource());
        if (!workerSpecs.containsKey(WorkerId.DEFAULT)
                || workerSpecs.get(WorkerId.DEFAULT).executionMode() != WorkerSpec.ExecutionMode.TRANSACTIONAL) {
            throw new IllegalArgumentException("Transactional DEFAULT worker is required");
        }
        var registeredWorkerIds = java.util.Set.copyOf(workerSpecs.keySet());
        var processDefinitionRegistry = new ProcessDefinitionRegistry(registeredWorkerIds::contains);
        processDefinitionRegistry.registerAll(processDefinitions);
        var managementService = new ManagementService(database, effectiveDatabaseDialect);
        var processInstanceFactory = new ProcessInstanceFactory(clock, objectMapper, processDefinitionRegistry);
        var processInstanceDao = new ProcessInstanceDao(database, clock, processInstanceFactory,
                saveProcessHistory, effectiveMetrics);
        var processSearchService = new ProcessSearchService(database, objectMapper);
        var processMdc = new ProcessMdc(new MdcKeys(correlationIdMdcKey, processIdMdcKey, processTypeMdcKey));
        var stepExecutor = new StepExecutor(processMdc, effectiveMetrics, clock);
        var stepErrorHandler = new StepErrorHandler(clock, database, processInstanceDao, processMdc,
                effectiveMetrics);
        var leaseDao = new LeaseDao(database, effectiveDatabaseDialect);
        var leaseReleaser = new LeaseReleaser(leaseDao, effectiveMetrics);
        var dispatchSignal = new ProcessDispatchSignal();
        var workerFactory = new WorkerFactory(database, processInstanceDao, stepExecutor, stepErrorHandler,
                effectiveMetrics, leaseReleaser, dispatchSignal::wakeUp);
        var workerRegistry = new WorkerRegistry(workerFactory);

        var applicationInstanceLifecycle = new ApplicationInstanceLifecycle(instanceHeartbeatInterval,
                instanceTimeout, new ApplicationInstanceDao(database), effectiveMetrics);
        var dispatcher = new LeaseDispatcher(applicationInstanceLifecycle::ownerId,
                leaseDao, leaseReleaser, workerRegistry, effectiveMetrics, clock);

        var processDispatchScheduler = new ProcessDispatchScheduler(minProcessDispatchInterval,
                maxProcessDispatchInterval,
                dispatcher, dispatchSignal, clock);
        var processRuntimeService = new ProcessRuntimeService(clock, database, processInstanceDao,
                processInstanceFactory, dispatchSignal, effectiveMetrics, processMdc, stepExecutor, stepErrorHandler);
        effectiveMetrics.registerProcessCount(ProcessStatus.ACTIVE,
                () -> processInstanceDao.countByStatus(ProcessStatus.ACTIVE));
        effectiveMetrics.registerProcessCount(ProcessStatus.FAILED,
                () -> processInstanceDao.countByStatus(ProcessStatus.FAILED));

        workerRegistry.registerAll(workerSpecs.values());

        return new ProcessEngine(processSearchService,
                workerRegistry, processDefinitionRegistry, processDispatchScheduler, managementService,
                processRuntimeService, applicationInstanceLifecycle, clock,
                databaseMigrationEnabled, dispatcherEnabled);
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
