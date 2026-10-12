package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.TestDatabases;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.definition.ProcessDefinition;
import io.github.neherim.ruslo.core.api.definition.ProcessType;
import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.definition.ProcessDefinitionRegistry;
import io.github.neherim.ruslo.core.dispatcher.lease.ApplicationInstanceDao;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseDao;
import io.github.neherim.ruslo.core.dispatcher.lease.LeaseReleaser;
import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;
import io.github.neherim.ruslo.core.runtime.logging.MdcKeys;
import io.github.neherim.ruslo.core.runtime.logging.ProcessMdc;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstance;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceDao;
import io.github.neherim.ruslo.core.runtime.process.ProcessInstanceFactory;
import io.github.neherim.ruslo.core.runtime.worker.impl.ProcessWorker;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.ProcessEngineMetrics;
import io.github.neherim.ruslo.core.utils.JsonUtils;
import io.github.neherim.ruslo.core.utils.thread.CurrentThreadExecutorService;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;

public final class WorkerTestFixture {
    private static final Duration INSTANCE_TIMEOUT = Duration.ofMinutes(1);
    private final Database database;
    private final ProcessInstanceFactory processInstanceFactory;
    private final ProcessInstanceDao processInstanceDao;
    private final ProcessEngineMetrics metrics = new ProcessEngineMetrics() {
    };
    private final ProcessMdc processMdc;
    private final LeaseDao leaseDao;
    private final UUID instanceId = UUID.randomUUID();
    private final Clock clock = Clock.systemUTC();

    private WorkerTestFixture(Database database, ProcessDefinition... definitions) {
        this.database = database;
        var registry = new ProcessDefinitionRegistry(List.of(definitions));
        this.processInstanceFactory = new ProcessInstanceFactory(clock, JsonUtils.DEFAULT_OBJECT_MAPPER, registry);
        this.processInstanceDao = new ProcessInstanceDao(database, clock, processInstanceFactory, true, metrics);
        this.processMdc = new ProcessMdc(MdcKeys.defaults());
        this.leaseDao = new LeaseDao(database, TestDatabases.dialect());
        new ApplicationInstanceDao(database).register(instanceId, INSTANCE_TIMEOUT);
    }

    public static WorkerTestFixture with(Database database, ProcessDefinition... definitions) {
        return new WorkerTestFixture(database, definitions);
    }

    public ProcessInstanceDao dao() {
        return processInstanceDao;
    }

    public ProcessInstance newProcess(ProcessType type, String correlationId) {
        return processInstanceFactory.newInstance(type, Instant.now(), null, correlationId, Map.of());
    }

    public ProcessInstance insertNewProcess(ProcessType type, String correlationId) {
        var process = newProcess(type, correlationId);
        processInstanceDao.insert(process);
        return process;
    }

    public ProcessLease leaseTask(ProcessInstance process) {
        return claim(process);
    }

    /** Leases the given process through a regular bounded worker claim. */
    public ProcessLease claim(ProcessInstance process) {
        database.update("update xs_process_instance set scheduled_at = ?, available_at = ? where id = ?",
                java.sql.Timestamp.from(Instant.EPOCH), java.sql.Timestamp.from(Instant.EPOCH), process.getId());
        var lease = leaseDao.claimReadyForWorker(instanceId, process.getWorkerId().orElseThrow(), 1, now())
            .stream()
            .findFirst()
            .orElseThrow();
        if (!lease.processId().equals(process.getId())) {
            throw new IllegalStateException("Unexpected process claimed: " + lease.processId());
        }
        return lease;
    }

    public ProcessLease task(ProcessInstance process) {
        return leaseTask(process);
    }

    public ProcessLease leaseTask(ProcessLease lease) {
        return lease;
    }

    public LeaseDao leaseDao() {
        return leaseDao;
    }

    public StepErrorHandler stepErrorHandler() {
        return new StepErrorHandler(clock, database, processInstanceDao, processMdc, metrics);
    }

    public ProcessWorker transactionalLeaseWorker(ExecutorService executorService, Runnable completionHook) {
        return worker(WorkerSpec.builder(WorkerId.DEFAULT).transactional().maxConcurrency(10).build(),
                true, executorService, completionHook);
    }

    public ProcessWorker transactionalWorker() {
        return transactionalLeaseWorker(new CurrentThreadExecutorService(), () -> {});
    }

    public ProcessWorker nonTransactionalLeaseWorker(
            int maxConcurrency,
            ExecutorService executorService,
            Runnable completionHook
    ) {
        return nonTransactionalLeaseWorker(maxConcurrency, true, executorService, completionHook);
    }

    public ProcessWorker nonTransactionalLeaseWorker(
            int maxConcurrency,
            boolean shutdownExecutor,
            ExecutorService executorService,
            Runnable completionHook
    ) {
        return worker(WorkerSpec.builder(WorkerId.DEFAULT).nonTransactional().maxConcurrency(maxConcurrency).build(),
                shutdownExecutor, executorService, completionHook);
    }

    private ProcessWorker worker(
            WorkerSpec spec,
            boolean shutdownExecutor,
            ExecutorService executorService,
            Runnable completionHook
    ) {
        return new ProcessWorker(spec, executorService, shutdownExecutor, database, processInstanceDao,
                new StepExecutor(processMdc, metrics, clock), stepErrorHandler(), metrics,
                new LeaseReleaser(leaseDao, metrics), completionHook);
    }

    public WorkerFactory workerFactory() {
        return new WorkerFactory(database, processInstanceDao, new StepExecutor(processMdc, metrics, clock),
                stepErrorHandler(), metrics, new LeaseReleaser(leaseDao, metrics), () -> {});
    }

    private Instant now() {
        return clock.instant();
    }
}
