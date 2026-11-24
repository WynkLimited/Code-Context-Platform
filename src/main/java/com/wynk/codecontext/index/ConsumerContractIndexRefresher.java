package com.wynk.codecontext.index;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.repo.GitRepositoryManager;
import jakarta.annotation.PreDestroy;

import java.util.List;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Rebuilds only relationships touching a newly extracted generation; immutable peer indexes are reused. */
@Component
public final class ConsumerContractIndexRefresher {
    private final JdbcTemplate jdbc;
    private final GitRepositoryManager git;
    private final PostgresContractIndexStore indexes;
    private final ThreadPoolExecutor refreshers=new ThreadPoolExecutor(2,2,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(50),r->{Thread t=new Thread(r,"contract-index-refresh");t.setDaemon(true);return t;});
    private static final int MAX_REFRESH_CANDIDATES=50;
    public ConsumerContractIndexRefresher(JdbcTemplate jdbc,GitRepositoryManager git,PostgresContractIndexStore indexes) { this.jdbc=jdbc;this.git=git;this.indexes=indexes; }

    /**
     * Refreshes only repositories with a published import of a changed identity, plus repositories
     * without any index. Work is asynchronous and bounded; callers must keep the current result
     * incomplete until queued jobs publish.
     */
    public RefreshRequest refreshRelevant(String team,UUID originRepository,Collection<String> changedKeys) {
        List<String> keys=changedKeys==null?List.of():changedKeys.stream().filter(k->k!=null&&!k.isBlank()).distinct().toList();
        if(keys.isEmpty()) return new RefreshRequest(0,false);
        String placeholders=String.join(",",java.util.Collections.nCopies(keys.size(),"?"));
        String sql="""
SELECT r.id,r.owner,r.name,r.clone_url,r.team,r.default_branch,r.repository_type,r.mirror_path
FROM repositories r JOIN team_repository_access a ON a.repository_id=r.id
WHERE a.team=? AND r.enabled=true AND r.id<>?
 AND (NOT EXISTS(SELECT 1 FROM index_generations g WHERE g.repository_id=r.id AND g.published)
      OR EXISTS(SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id AND g.published WHERE g.repository_id=r.id AND c.role='IMPORT' AND c.canonical_key IN (%s)))
ORDER BY r.owner,r.name LIMIT ?
""".formatted(placeholders);
        Object[] args=new Object[2+keys.size()+1];args[0]=team;args[1]=originRepository;for(int i=0;i<keys.size();i++)args[i+2]=keys.get(i);args[args.length-1]=MAX_REFRESH_CANDIDATES+1;
        List<RepositoryRecord> candidates=jdbc.query(sql,(rs,n)->new RepositoryRecord(rs.getObject("id",UUID.class),rs.getString("owner"),rs.getString("name"),rs.getString("clone_url"),rs.getString("team"),rs.getString("default_branch"),rs.getString("repository_type"),rs.getString("mirror_path")),args);
        boolean truncated=candidates.size()>MAX_REFRESH_CANDIDATES;
        int scheduled=0;
        for(RepositoryRecord repo:candidates.stream().limit(MAX_REFRESH_CANDIDATES).toList()) {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM index_jobs WHERE repository_id=? AND state IN ('QUEUED','RUNNING'))",Boolean.class,repo.id()))) continue;
            try { refreshers.execute(()->refreshOne(repo)); scheduled++; }
            catch(java.util.concurrent.RejectedExecutionException ignored) { truncated=true; break; }
        }
        return new RefreshRequest(scheduled,truncated);
    }

    private void refreshOne(RepositoryRecord repo) {
        try {
            var root=git.sync(repo);String sha=git.head(root);
            var published=indexes.published(repo.id());
            if(published.isPresent()&&published.get().index().commit().equals(sha)&&indexes.freshness(published.get())==com.wynk.codecontext.port.contract.ContractModels.IndexFreshness.CURRENT)return;
            String key="contract-refresh:"+repo.id()+":"+sha;
            jdbc.update("INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state) VALUES(?,?,?,'QUEUED') ON CONFLICT(idempotency_key) DO NOTHING",repo.id(),sha,key);
        } catch(Exception e) {
            org.jboss.logging.Logger.getLogger(ConsumerContractIndexRefresher.class).warnf("contract.index.refresh.failed repo=%s reason=%s",repo.key(),e.getMessage());
        }
    }

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
""",generation,generation);
    }

    /** Remove links involving superseded or failed generations after the replacement publishes. */
    public void pruneSupersededRelationships() {
        jdbc.update("""
DELETE FROM contract_links l
WHERE EXISTS (SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id WHERE c.id=l.provider_contract AND g.published=false)
   OR EXISTS (SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id WHERE c.id=l.consumer_contract AND g.published=false)
""");
    }

    @PreDestroy public void shutdown(){refreshers.shutdownNow();}
    public record RefreshRequest(int scheduled,boolean truncated){}
}
