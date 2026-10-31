package io.github.neherim.ruslo.core.dispatcher.lease;

import io.github.neherim.ruslo.core.api.definition.WorkerId;
import io.github.neherim.ruslo.core.database.DatabaseDialect;
import io.github.neherim.ruslo.core.spi.Database;
import io.github.neherim.ruslo.core.spi.RowMapper;
import io.github.neherim.ruslo.core.utils.DateTimeUtils;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Short transactions that claim and release process leases. */
public final class LeaseDao {
    private static final String POSTGRESQL_CLAIM_SQL = """
            with candidates as (
                select
                    p.ctid
                from xs_process_instance p
                where p.worker_id = ?
                  and p.status = 'ACTIVE'
                  and p.lease_owner is null
                  and p.next_run_at <= ?
                  and exists (
                      select 1
                      from xs_application_instance a
                      where a.id = ?
                        and a.expires_at >= current_timestamp
                  )
                order by p.next_run_at, p.id
                limit ?
                for update of p skip locked
            )
            update xs_process_instance p
            set lease_owner = ?
            from candidates c
            where p.ctid = c.ctid
            returning p.id
            """;
    private static final String H2_CLAIM_SQL = """
            select claimed.id
            from final table (
                update xs_process_instance
                set lease_owner = ?
                where id in (
                    select p.id
                    from xs_process_instance p
                    where p.worker_id = ?
                      and p.status = 'ACTIVE'
                      and p.lease_owner is null
                      and p.next_run_at <= ?
                      and exists (
                          select 1
                          from xs_application_instance a
                          where a.id = ?
                            and a.expires_at >= current_timestamp
                      )
                    order by p.next_run_at, p.id
                    limit ?
                    for update skip locked
                )
            ) claimed
            order by claimed.next_run_at, claimed.id
            """;
    private static final String NEXT_RUN_AT_SQL = """
            select min(next_run_at)
            from xs_process_instance
            where worker_id = ?
              and status = 'ACTIVE'
              and lease_owner is null
              and next_run_at is not null
            """;
    private static final String RELEASE_SQL = """
            update xs_process_instance
            set lease_owner = null
            where id = ? and lease_owner = ?
            """;

    private final Database database;
    private final DatabaseDialect dialect;

    public LeaseDao(Database database, DatabaseDialect dialect) {
        this.database = Objects.requireNonNull(database, "Database must not be null");
        this.dialect = Objects.requireNonNull(dialect, "Database dialect must not be null");
    }

    /**
     * Claims ready processes of the worker.
     *
     * @param ownerId current registration id of this instance
     * @param now     engine time to check process readiness against; owner liveness uses database time
     */
    public List<ProcessLease> claimReadyForWorker(UUID ownerId, WorkerId workerId, int limit, Instant now) {
        if (limit <= 0) {
            return List.of();
        }
        RowMapper<ProcessLease> mapper = rs -> new ProcessLease(rs.getObject("id", UUID.class), ownerId);
        return switch (dialect) {
            case POSTGRESQL -> database.queryForList(POSTGRESQL_CLAIM_SQL, mapper,
                    workerId.name(), now, ownerId, limit, ownerId);
            case H2 -> database.queryForList(H2_CLAIM_SQL, mapper,
                    ownerId, workerId.name(), now, ownerId, limit);
        };
    }

    /**
     * Returns the earliest {@code next_run_at} of an unclaimed process of the worker, including one already passed;
     * empty if no process is waiting.
     */
    public Optional<Instant> findNextRunAt(WorkerId workerId) {
        return database.queryForObject(NEXT_RUN_AT_SQL,
                rs -> DateTimeUtils.toInstant(rs.getTimestamp(1)), workerId.name());
    }

    /**
     * Releases the lease if the process still belongs to its owner.
     *
     * @return {@code true} if the owner still held the process
     */
    public boolean release(ProcessLease lease) {
        return database.update(RELEASE_SQL, lease.processId(), lease.ownerId()) == 1;
    }
}
