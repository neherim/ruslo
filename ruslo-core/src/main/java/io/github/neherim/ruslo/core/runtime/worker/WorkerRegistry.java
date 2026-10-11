package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.api.exception.WorkerNotRegisteredException;

import java.util.*;

/** Registry of workers. */
public class WorkerRegistry {
    private final WorkerFactory workerFactory;
    private final Map<WorkerId, Worker> workers = new HashMap<>();

    public WorkerRegistry(WorkerFactory workerFactory) {
        this.workerFactory = workerFactory;
    }

    /** Builds and registers a worker for each spec. */
    public void registerAll(Collection<WorkerSpec> workerSpecs) {
        workerSpecs.forEach(this::register);
    }

    /** Builds and registers a worker for the spec. */
    public void register(WorkerSpec spec) {
        var worker = workerFactory.build(spec);
        this.workers.put(worker.getId(), worker);
    }

    /** Returns the specs of all registered workers. */
    public Set<WorkerSpec> getAllWorkerSpecs() {
        var specs = new HashSet<WorkerSpec>();
        workers.values().forEach(worker -> specs.add(worker.getSpec()));
        return specs;
    }

    /**
     * Returns the worker with the id.
     *
     * @throws WorkerNotRegisteredException if no worker with the id is registered
     */
    public Worker getWorker(WorkerId id) {
        var worker = workers.get(id);
        if (worker == null) {
            throw new WorkerNotRegisteredException(id);
        }
        return worker;
    }

    /** Returns all registered workers. */
    public Collection<Worker> getAllWorkers() {
        return workers.values();
    }

    /** Closes all registered workers. */
    public void close() {
        workers.values().forEach(Worker::close);
    }
}
