package io.github.neherim.ruslo.core.runtime.worker;

import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.api.WorkerSpec;
import io.github.neherim.ruslo.core.dispatcher.lease.ProcessLease;

/** Executes steps of processes routed to one {@link WorkerId}, in parallel up to its capacity. */
public interface Worker extends AutoCloseable {
    WorkerSpec getSpec();

    default WorkerId getId() {
        return getSpec().workerId();
    }

    /** Returns the number of free execution slots. */
    int getAvailableCapacity();

    /**
     * Accepts the lease for execution at once if an execution slot is free.
     *
     * @return {@code false} if the lease was not accepted; the dispatcher must then release it with fencing
     */
    boolean tryExecute(ProcessLease lease);

    /** Stops accepting leases and releases the worker resources. */
    @Override
    void close();
}
