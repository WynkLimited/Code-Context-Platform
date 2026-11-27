package com.wynk.codecontext.repo;

import com.wynk.codecontext.index.IndexingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Durable, replica-coalesced recovery for missed pushes; normal indexing retains all publication fences. */
@Service
public final class RepositoryRefresh {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(RepositoryRefresh.class);
    private final JdbcTemplate jdbc;
    private final IndexingService indexing;
    private final TransactionTemplate transactions;
    private final int intervalSeconds;
    public RepositoryRefresh(JdbcTemplate jdbc,IndexingService indexing,PlatformTransactionManager tx,
            @Value("${REPOSITORY_REFRESH_INTERVAL_SECONDS:300}") int intervalSeconds) {
        if(intervalSeconds<30||intervalSeconds>86400)throw new IllegalArgumentException("repository refresh interval must be 30..86400 seconds");
        this.jdbc=jdbc;this.indexing=indexing;this.transactions=new TransactionTemplate(tx);this.intervalSeconds=intervalSeconds;
    }
    @Scheduled(fixedDelayString="${REPOSITORY_REFRESH_POLL_MS:60000}",initialDelayString="${REPOSITORY_REFRESH_POLL_MS:60000}")
    public void poll() {
        try {for(int i=0;i<10;i++)if(!enqueueOneDue())break;}
        catch(org.springframework.dao.DataAccessException failure) {
            LOG.warn("repository.refresh.schedule.failed category={}",failure.getClass().getSimpleName());
        }
    }
    public boolean enqueueOneDue() {
        return Boolean.TRUE.equals(transactions.execute(status->{
            var candidates=jdbc.query("""
                SELECT r.id,r.owner,r.name,r.configuration_revision FROM repositories r
                WHERE r.enabled AND r.refresh_due_at<=now()
                  AND NOT EXISTS(SELECT 1 FROM index_jobs j WHERE j.repository_id=r.id AND j.state IN ('QUEUED','RUNNING'))
                  AND NOT EXISTS(SELECT 1 FROM repository_lifecycle_work w WHERE w.repository_id=r.id AND w.state<>'DONE')
                ORDER BY r.refresh_due_at,r.id FOR UPDATE OF r SKIP LOCKED LIMIT 1
                """,(rs,n)->new Candidate(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getLong(4)));
            if(candidates.isEmpty())return false;
            var repository=candidates.getFirst();
            UUID job=indexing.enqueue(repository.owner(),repository.name(),"HEAD",
                    "scheduled-head:"+repository.id()+":"+repository.revision()+":"+UUID.randomUUID());
            jdbc.update("UPDATE repositories SET last_refresh_job_id=?,last_refresh_scheduled_at=now(),refresh_due_at=now()+(? * interval '1 second') WHERE id=?",
                    job,intervalSeconds,repository.id());
            return true;
        }));
    }
    private record Candidate(UUID id,String owner,String name,long revision) {}
}
