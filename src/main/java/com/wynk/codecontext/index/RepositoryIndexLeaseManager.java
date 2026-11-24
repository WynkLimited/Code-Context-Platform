package com.wynk.codecontext.index;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** PostgreSQL-backed per-repository exclusion shared by all application replicas. */
@Component
public final class RepositoryIndexLeaseManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(RepositoryIndexLeaseManager.class);
    private static final Duration LEASE_DURATION = Duration.ofMinutes(5);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1, task -> {
        Thread thread = new Thread(task, "repository-index-lease-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public RepositoryIndexLeaseManager(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Lease> tryAcquire(UUID repositoryId, UUID jobId) {
        UUID token = UUID.randomUUID();
        int claimed = jdbc.update("""
                INSERT INTO repository_index_leases(repository_id,lease_token,lease_expires_at,updated_at)
                VALUES(?,?,now()+(? * interval '1 millisecond'),now())
                ON CONFLICT(repository_id) DO UPDATE SET lease_token=excluded.lease_token,
                  lease_expires_at=excluded.lease_expires_at,updated_at=now()
                WHERE repository_index_leases.lease_expires_at < now()
                """, repositoryId, token, LEASE_DURATION.toMillis());
        if (claimed == 0) return Optional.empty();

        Lease lease = new Lease(repositoryId, jobId, token);
        lease.heartbeat = heartbeats.scheduleAtFixedRate(lease::renew,
                HEARTBEAT_INTERVAL.toMillis(), HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        return Optional.of(lease);
    }

    @PreDestroy
    public void shutdown() {
        heartbeats.shutdownNow();
    }

    public final class Lease implements AutoCloseable {
        private final UUID repositoryId;
        private final UUID jobId;
        private final UUID token;
        private final AtomicBoolean valid = new AtomicBoolean(true);
        private volatile ScheduledFuture<?> heartbeat;

        private Lease(UUID repositoryId, UUID jobId, UUID token) {
            this.repositoryId = repositoryId;
            this.jobId = jobId;
            this.token = token;
        }

        private void renew() {
            if (!valid.get()) return;
            try {
                int renewed = jdbc.update("""
                        UPDATE repository_index_leases
                        SET lease_expires_at=now()+(? * interval '1 millisecond'),updated_at=now()
                        WHERE repository_id=? AND lease_token=? AND lease_expires_at>now()
                        """, LEASE_DURATION.toMillis(), repositoryId, token);
                if (renewed != 1) {
                    lose("lease ownership changed or expired");
                    return;
                }
                jdbc.update("UPDATE index_jobs SET updated_at=now() WHERE id=? AND state='RUNNING'", jobId);
            } catch (RuntimeException failure) {
                lose("lease heartbeat failed: " + failure.getClass().getSimpleName());
            }
        }

        /** Lock ownership until the enclosing publication/cleanup transaction commits. */
        public void fenceTransaction() {
            if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("repository fencing requires a transaction");
            if (!valid.get()) throw new IllegalStateException("repository indexing lease is no longer valid");
            var owned = jdbc.query("""
                    SELECT lease_token FROM repository_index_leases
                    WHERE repository_id=? AND lease_token=? AND lease_expires_at>clock_timestamp()
                    FOR UPDATE
                    """, (rs, row) -> rs.getObject(1, UUID.class), repositoryId, token);
            if (owned.isEmpty()) throw new IllegalStateException("repository indexing lease is no longer valid");
        }

        public void assertOwned() {
            if (!valid.get()) throw new IllegalStateException("repository indexing lease is no longer valid");
            Boolean owned = jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM repository_index_leases
                                  WHERE repository_id=? AND lease_token=? AND lease_expires_at>now())
                    """, Boolean.class, repositoryId, token);
            if (!Boolean.TRUE.equals(owned)) {
                lose("lease ownership check failed");
                throw new IllegalStateException("repository indexing lease is no longer valid");
            }
        }

        private void lose(String reason) {
            if (valid.compareAndSet(true, false)) {
                LOGGER.warn("repository.index.lease.lost repo={} job={} reason={}", repositoryId, jobId, reason);
            }
        }

        @Override
        public void close() {
            valid.set(false);
            ScheduledFuture<?> task = heartbeat;
            if (task != null) task.cancel(false);
            jdbc.update("DELETE FROM repository_index_leases WHERE repository_id=? AND lease_token=?", repositoryId, token);
        }
    }
}
