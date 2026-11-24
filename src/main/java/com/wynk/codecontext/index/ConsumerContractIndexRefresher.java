package com.wynk.codecontext.index;

import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.repo.GitRepositoryManager;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Durably refreshes repositories that may have been unresolved when a provider changed. */
@Component
public final class ConsumerContractIndexRefresher {
    private static final int MAX_REFRESH_CANDIDATES = 50;
    private static final int MAX_PENDING_REFRESH_JOBS = 5_000;
    private static final int MAX_ATTEMPTS = 5;
    private final JdbcTemplate jdbc;
    private final GitRepositoryManager git;
    private final PostgresContractIndexStore indexes;
    private final ThreadPoolExecutor refreshers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(50), task -> {
                Thread thread = new Thread(task, "contract-index-refresh");
                thread.setDaemon(true);
                return thread;
            });

    public ConsumerContractIndexRefresher(JdbcTemplate jdbc, GitRepositoryManager git,
                                          PostgresContractIndexStore indexes) {
        this.jdbc = jdbc;
        this.git = git;
        this.indexes = indexes;
    }

    /** Persist bounded work before returning; worker-process restarts cannot lose queued refreshes. */
    public RefreshRequest refreshRelevant(String team, UUID originRepository, UUID originGeneration,
                                          Collection<String> changedKeys) {
        List<String> keys = changedKeys == null ? List.of() : changedKeys.stream()
                .filter(key -> key != null && !key.isBlank()).distinct().sorted().toList();
        if (keys.isEmpty()) return new RefreshRequest(0, false);

        List<String> placeholders = java.util.Collections.nCopies(keys.size(), "?");
        String sql = """
                SELECT r.id,r.owner,r.name,r.clone_url,r.team,r.default_branch,r.repository_type,r.mirror_path
                FROM repositories r JOIN team_repository_access a ON a.repository_id=r.id
                WHERE a.team=? AND r.enabled=true AND r.id<>?
                 AND (NOT EXISTS(SELECT 1 FROM index_generations g WHERE g.repository_id=r.id AND g.published)
                      OR EXISTS(SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id AND g.published
                                WHERE g.repository_id=r.id AND c.role='IMPORT' AND c.canonical_key IN (%s)))
                ORDER BY r.owner,r.name LIMIT ?
                """.formatted(String.join(",", placeholders));
        Object[] queryArgs = new Object[3 + keys.size()];
        queryArgs[0] = team;
        queryArgs[1] = originRepository;
        for (int i = 0; i < keys.size(); i++) queryArgs[i + 2] = keys.get(i);
        queryArgs[queryArgs.length - 1] = MAX_REFRESH_CANDIDATES + 1;
        List<RepositoryRecord> candidates = jdbc.query(sql, (rs, row) -> new RepositoryRecord(
                rs.getObject("id", UUID.class), rs.getString("owner"), rs.getString("name"),
                rs.getString("clone_url"), rs.getString("team"), rs.getString("default_branch"),
                rs.getString("repository_type"), rs.getString("mirror_path")), queryArgs);
        boolean truncated = candidates.size() > MAX_REFRESH_CANDIDATES;
        int pending = jdbc.queryForObject("SELECT count(*) FROM contract_refresh_jobs WHERE state IN ('QUEUED','RUNNING')", Integer.class);
        int scheduled = 0;
        for (RepositoryRecord repository : candidates.stream().limit(MAX_REFRESH_CANDIDATES).toList()) {
            if (pending + scheduled >= MAX_PENDING_REFRESH_JOBS) {
                truncated = true;
                break;
            }
            String key = "contract-refresh:" + originGeneration + ":" + repository.id();
            scheduled += jdbc.update("""
                    INSERT INTO contract_refresh_jobs(team,origin_repository_id,origin_generation_id,repository_id,
                                                      changed_keys,idempotency_key,state)
                    VALUES(?,?,?,?,?,?,'QUEUED') ON CONFLICT(idempotency_key) DO NOTHING
                    """, team, originRepository, originGeneration, repository.id(),
                    new SqlArrayValue("text", keys.toArray()), key);
        }
        return new RefreshRequest(scheduled, truncated);
    }

    @Scheduled(fixedDelayString = "${CONTRACT_REFRESH_POLL_MS:2000}")
    public void poll() {
        jdbc.update("""
                UPDATE contract_refresh_jobs
                SET state=CASE WHEN attempt>=? THEN 'FAILED' ELSE 'QUEUED' END,
                    available_at=now(),lease_expires_at=NULL,error='worker lease expired',updated_at=now()
                WHERE state='RUNNING' AND lease_expires_at < now()
                """, MAX_ATTEMPTS);
        while (refreshers.getActiveCount() + refreshers.getQueue().size() < 52) {
            List<UUID> claimed = jdbc.query("""
                    WITH candidate AS (
                      SELECT id FROM contract_refresh_jobs
                      WHERE state='QUEUED' AND available_at<=now()
                      ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE contract_refresh_jobs SET state='RUNNING',attempt=attempt+1,
                      lease_expires_at=now()+interval '10 minutes',updated_at=now()
                    WHERE id=(SELECT id FROM candidate) RETURNING id
                    """, (rs, row) -> rs.getObject(1, UUID.class));
            if (claimed.isEmpty()) break;
            UUID jobId = claimed.get(0);
            try {
                refreshers.execute(() -> process(jobId));
            } catch (RejectedExecutionException rejected) {
                jdbc.update("UPDATE contract_refresh_jobs SET state='QUEUED',lease_expires_at=NULL,updated_at=now() WHERE id=?", jobId);
                break;
            }
        }
    }

    private void process(UUID jobId) {
        RefreshJob job = jdbc.queryForObject("""
                SELECT j.repository_id,j.origin_generation_id,j.attempt,
                       r.id,r.owner,r.name,r.clone_url,r.team,r.default_branch,r.repository_type,r.mirror_path
                FROM contract_refresh_jobs j JOIN repositories r ON r.id=j.repository_id WHERE j.id=?
                """, (rs, row) -> new RefreshJob(jobId, rs.getObject("repository_id", UUID.class),
                rs.getObject("origin_generation_id", UUID.class), rs.getInt("attempt"),
                new RepositoryRecord(rs.getObject("id", UUID.class), rs.getString("owner"), rs.getString("name"),
                        rs.getString("clone_url"), rs.getString("team"), rs.getString("default_branch"),
                        rs.getString("repository_type"), rs.getString("mirror_path"))), jobId);
        try {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?", Boolean.class, job.repository.id()))) {
                jdbc.update("UPDATE contract_refresh_jobs SET state='SUCCEEDED',lease_expires_at=NULL,updated_at=now() WHERE id=?", job.id);
                return;
            }
            var mirror = git.sync(job.repository);
            String commit = git.head(mirror);
            var published = indexes.published(job.repository.id());
            if (published.isEmpty() || !published.get().index().commit().equals(commit)
                    || indexes.freshness(published.get()) != com.wynk.codecontext.port.contract.ContractModels.IndexFreshness.CURRENT) {
                String key = "contract-refresh:" + job.originGeneration + ":" + job.repository.id() + ":" + commit;
                jdbc.update("""
                        INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state)
                        VALUES(?,?,?,'QUEUED') ON CONFLICT(idempotency_key) DO UPDATE
                        SET state=CASE WHEN index_jobs.state='FAILED' THEN 'QUEUED' ELSE index_jobs.state END,
                            attempt=CASE WHEN index_jobs.state='FAILED' THEN 0 ELSE index_jobs.attempt END,
                            available_at=CASE WHEN index_jobs.state='FAILED' THEN now() ELSE index_jobs.available_at END,
                            error=CASE WHEN index_jobs.state='FAILED' THEN NULL ELSE index_jobs.error END,
                            updated_at=CASE WHEN index_jobs.state='FAILED' THEN now() ELSE index_jobs.updated_at END
                        """, job.repository.id(), commit, key);
            }
            jdbc.update("UPDATE contract_refresh_jobs SET state='SUCCEEDED',lease_expires_at=NULL,error=NULL,updated_at=now() WHERE id=?", job.id);
        } catch (Exception failure) {
            int delay = Math.min(300, 5 * (1 << Math.min(6, Math.max(0, job.attempt - 1))));
            jdbc.update("""
                    UPDATE contract_refresh_jobs
                    SET state=CASE WHEN attempt>=? THEN 'FAILED' ELSE 'QUEUED' END,
                        available_at=now()+(? * interval '1 second'),lease_expires_at=NULL,error=?,updated_at=now()
                    WHERE id=?
                    """, MAX_ATTEMPTS, delay, truncate(failure.getMessage()), job.id);
            org.jboss.logging.Logger.getLogger(ConsumerContractIndexRefresher.class)
                    .warnf("contract.index.refresh.failed job=%s repo=%s reason=%s", job.id, job.repository.key(), truncate(failure.getMessage()));
        }
    }

    private static String truncate(String message) {
        if (message == null) return "refresh failed";
        return message.substring(0, Math.min(500, message.length()));
    }

    /** Bind current generation relationships and prune stale links as part of generation publication. */
    public void refreshGeneration(UUID generation) {
        jdbc.update("""
                INSERT INTO contract_links(provider_contract,consumer_contract,confidence,evidence)
                SELECT p.id,c.id,least(p.confidence,c.confidence),jsonb_build_array(
                  jsonb_build_object('providerFile',p.file_path,'providerLine',p.line,'providerEvidence',p.evidence,'providerVersion',p.dependency_version,'providerCommit',pg.commit_sha),
                  jsonb_build_object('consumerFile',c.file_path,'consumerLine',c.line,'consumerEvidence',c.evidence,'consumerVersion',c.dependency_version,'consumerCommit',cg.commit_sha))
                FROM contracts p JOIN contracts c ON p.contract_type=c.contract_type
                JOIN index_generations pg ON pg.id=p.generation_id JOIN index_generations cg ON cg.id=c.generation_id
                WHERE p.role='EXPORT' AND c.role='IMPORT' AND pg.repository_id<>cg.repository_id
                  AND (p.canonical_key=c.canonical_key OR (p.contract_type='REST'
                    AND split_part(p.canonical_key,':',3)=split_part(c.canonical_key,':',3)
                    AND (split_part(c.canonical_key,':',2)='*' OR split_part(p.canonical_key,':',2)='*' OR split_part(p.canonical_key,':',2)=split_part(c.canonical_key,':',2))))
                  AND ((p.generation_id=? AND cg.published) OR (c.generation_id=? AND pg.published))
                ON CONFLICT(provider_contract,consumer_contract) DO UPDATE SET confidence=excluded.confidence,evidence=excluded.evidence
                """, generation, generation);
    }

    /** Remove links involving superseded or failed generations after the replacement publishes. */
    public void pruneSupersededRelationships() {
        jdbc.update("""
                DELETE FROM contract_links l
                WHERE EXISTS (SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id WHERE c.id=l.provider_contract AND g.published=false)
                   OR EXISTS (SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id WHERE c.id=l.consumer_contract AND g.published=false)
                """);
    }

    @PreDestroy
    public void shutdown() { refreshers.shutdownNow(); }

    private record RefreshJob(UUID id, UUID repositoryId, UUID originGeneration, int attempt, RepositoryRecord repository) { }
    public record RefreshRequest(int scheduled, boolean truncated) { }
}
