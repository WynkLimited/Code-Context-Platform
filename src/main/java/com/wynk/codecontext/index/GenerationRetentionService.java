package com.wynk.codecontext.index;

import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.search.ZoektIndexer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/** Deletes only unreferenced history; PostgreSQL owns the durable external-cleanup queue. */
@Service
public class GenerationRetentionService {
    private static final String PROTECTED = """
                      AND NOT EXISTS(SELECT 1 FROM index_jobs j WHERE j.repository_id=g.repository_id AND j.state IN ('QUEUED','RUNNING'))
                      AND g.id NOT IN (
                        SELECT h.id FROM index_generations h WHERE h.repository_id=g.repository_id
                        AND EXISTS(SELECT 1 FROM index_jobs j WHERE j.repository_id=h.repository_id AND j.commit_sha=h.commit_sha AND j.state='SUCCEEDED')
                        ORDER BY h.generation DESC LIMIT 2)
                      AND NOT EXISTS(SELECT 1 FROM cross_repo_impact_analyses a WHERE a.before_generation_id=g.id OR a.after_generation_id=g.id)
                      AND NOT EXISTS(SELECT 1 FROM contract_refresh_jobs f WHERE f.origin_generation_id=g.id)
                      AND NOT EXISTS(SELECT 1 FROM contracts c JOIN contract_links l ON l.provider_contract=c.id OR l.consumer_contract=c.id WHERE c.generation_id=g.id)
            """;
    private final JdbcTemplate jdbc;
    private final RepositoryIndexLeaseManager leases;
    private final ZoektIndexer zoekt;
    private final TransactionTemplate transactions;
    private final int days;
    private final int batch;

    public GenerationRetentionService(JdbcTemplate jdbc, RepositoryIndexLeaseManager leases, ZoektIndexer zoekt,
                                      PlatformTransactionManager tx,
                                      @Value("${GENERATION_RETENTION_DAYS:30}") int days,
                                      @Value("${GENERATION_GC_BATCH_SIZE:10}") int batch) {
        if (days < 1 || batch < 1 || batch > 100) throw new IllegalArgumentException("invalid generation retention policy");
        this.jdbc=jdbc; this.leases=leases; this.zoekt=zoekt;
        this.transactions=new TransactionTemplate(tx); this.days=days; this.batch=batch;
    }

    @Scheduled(fixedDelayString="${GENERATION_GC_POLL_MS:60000}", initialDelayString="${GENERATION_GC_POLL_MS:60000}")
    public void poll() {
        var candidates=jdbc.query("SELECT g.id,g.repository_id FROM index_generations g WHERE NOT g.published AND g.created_at<now()-(? * interval '1 day') " + PROTECTED + " ORDER BY g.created_at LIMIT ?",
                (rs,n)->new Candidate(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)),days,batch);
        for(var candidate:candidates) collect(candidate.generation,candidate.repository);
        for(int i=0;i<batch;i++) if(!cleanOne()) break;
    }

    boolean collect(UUID generation, UUID repository) {
        var claim=leases.tryAcquire(repository,UUID.randomUUID());
        if(claim.isEmpty()) return false;
        try(var lease=claim.get()) {
            return Boolean.TRUE.equals(transactions.execute(status->{
                lease.fenceTransaction();
                // Serialize disable/re-onboard and FK insertion of new jobs with the eligibility check.
                jdbc.query("SELECT id FROM repositories WHERE id=? FOR UPDATE",(rs,n)->rs.getObject(1,UUID.class),repository);
                var eligible=jdbc.query("""
                    SELECT g.id,r.owner,r.name FROM index_generations g JOIN repositories r ON r.id=g.repository_id
                    WHERE g.id=? AND g.repository_id=? AND NOT g.published
                      AND g.created_at<now()-(? * interval '1 day')
                    """ + PROTECTED + " FOR UPDATE OF g",(rs,n)->new String[]{rs.getString("owner"),rs.getString("name")},generation,repository,days);
                if(eligible.isEmpty()) return false;
                var identity=eligible.get(0);
                jdbc.update("INSERT INTO generation_cleanup_outbox(generation_id,repository_id,owner,name) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",generation,repository,identity[0],identity[1]);
                // Derived graph, contract, semantic and vector records use ON DELETE CASCADE.
                jdbc.update("DELETE FROM index_generations WHERE id=?",generation);
                return true;
            }));
        }
    }

    boolean cleanOne() {
        return Boolean.TRUE.equals(transactions.execute(status->{
            var work=jdbc.query("SELECT generation_id,repository_id,owner,name FROM generation_cleanup_outbox WHERE state='QUEUED' AND available_at<=now() ORDER BY available_at FOR UPDATE SKIP LOCKED LIMIT 1",
                    (rs,n)->new Cleanup(rs.getObject(1,UUID.class),new RepositoryRecord(rs.getObject(2,UUID.class),rs.getString(3),rs.getString(4),"","","","","")));
            if(work.isEmpty()) return false;
            var item=work.get(0);
            try {
                zoekt.removeGeneration(item.repository,item.generation);
                jdbc.update("UPDATE generation_cleanup_outbox SET state='DONE',error=NULL,updated_at=now() WHERE generation_id=?",item.generation);
            } catch(Exception failure) {
                // A worker crash rolls back this transaction; removal is safe to repeat for the exact generation UUID.
                jdbc.update("UPDATE generation_cleanup_outbox SET attempt=attempt+1,state=CASE WHEN attempt+1>=10 THEN 'FAILED' ELSE 'QUEUED' END,available_at=now()+interval '5 minutes',error=?,updated_at=now() WHERE generation_id=?",
                        failure.getClass().getSimpleName(),item.generation);
            }
            return true;
        }));
    }
    private record Candidate(UUID generation,UUID repository) {}
    private record Cleanup(UUID generation,RepositoryRecord repository) {}
}
