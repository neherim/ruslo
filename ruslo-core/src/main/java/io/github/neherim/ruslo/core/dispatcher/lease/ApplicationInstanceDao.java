package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.api.view.ApplicationInstanceInfo;
import io.github.neherim.ruslo.core.spi.Database;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Registers, heartbeats and recovers engine instances.
 *
 * <p>Instance liveness uses database time, not the engine clock: the database both computes and compares
 * {@code expires_at}, so clock skew between nodes does not affect recovery.
 */
public final class ApplicationInstanceDao {
    private static final String REGISTER_SQL = """
            insert into xs_application_instance (id, expires_at)
            values (?, current_timestamp + cast(? as bigint) * interval '0.001' second)
            """;
    private static final String HEARTBEAT_SQL = """
            update xs_application_instance
            set expires_at = current_timestamp + cast(? as bigint) * interval '0.001' second
            where id = ?
            """;
    private static final String DELETE_EXPIRED_SQL = """
            delete from xs_application_instance where expires_at < current_timestamp
            """;
    private static final String RELEASE_ORPHAN_LEASES_SQL = """
            update xs_process_instance set lease_owner = null
            where id in (
                select p.id
                from xs_process_instance p
                where p.lease_owner is not null
                  and not exists (select 1 from xs_application_instance a where a.id = p.lease_owner)
                for update skip locked
            )
            """;
    private static final String RELEASE_OWNER_SQL = """
            update xs_process_instance set lease_owner = null where lease_owner = ?
            """;
    private static final String DELETE_INSTANCE_SQL = "delete from xs_application_instance where id = ?";
    private static final String LIST_SQL = """
            select a.id, a.expires_at, current_timestamp as db_now,
                   (select count(*) from xs_process_instance p where p.lease_owner = a.id) as lease_count
            from xs_application_instance a
            order by a.id
            """;

    private final Database database;

    public ApplicationInstanceDao(Database database) {
        this.database = Objects.requireNonNull(database, "Database must not be null");
    }

    /**
     * Registers a new instance; the registration expires {@code timeout} after the current database time
     * unless renewed by {@link #heartbeat}.
     */
    public void register(UUID instanceId, Duration timeout) {
        database.update(REGISTER_SQL, instanceId, timeout.toMillis());
    }

    /**
     * Renews the registration to {@code timeout} after the current database time.
     *
     * @return {@code false} if the registration was already deleted
     */
    public boolean heartbeat(UUID instanceId, Duration timeout) {
        return database.update(HEARTBEAT_SQL, timeout.toMillis(), instanceId) == 1;
    }

    /**
     * Deletes expired registrations and releases every lease whose owner is not registered.
     *
     * <p>Rows locked by running transactions are skipped without waiting and released by the next call. Leases
     * claimed after their owner was deleted are also released by the next call; fencing on
     * {@code lease_owner + row_version} protects against the stale owner.
     */
    public RecoveryResult recover() {
        var removed = database.update(DELETE_EXPIRED_SQL);
        var released = database.update(RELEASE_ORPHAN_LEASES_SQL);
        return new RecoveryResult(released, removed);
    }

    /** Releases the instance's leases and deletes its registration atomically. */
    public void unregister(UUID instanceId) {
        database.doInTransaction(() -> {
            database.update(RELEASE_OWNER_SQL, instanceId);
            database.update(DELETE_INSTANCE_SQL, instanceId);
        });
    }

    /**
     * Returns registered instances ordered by id, with their claimed process counts. Expiry is measured
     * against database time, like liveness.
     *
     * @param currentId registration id of this engine, or {@code null} if it is not registered
     */
    public List<ApplicationInstanceInfo> list(UUID currentId) {
        return database.queryForList(LIST_SQL, rs -> {
            var id = rs.getObject("id", UUID.class);
            var expiresIn = Duration.between(rs.getTimestamp("db_now").toInstant(),
                    rs.getTimestamp("expires_at").toInstant());
            return new ApplicationInstanceInfo(id, expiresIn, rs.getLong("lease_count"), id.equals(currentId));
        });
    }

    public record RecoveryResult(int releasedLeases, int removedInstances) {
    }
}
