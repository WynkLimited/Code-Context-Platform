package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.index.IndexingService;
import com.wynk.codecontext.index.RepositoryIndexLeaseManager;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.security.ApiKeyRoster;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stable repository UUIDs, immediate access suspension, fenced rebaseline and durable old-path cleanup. */
@Service
public final class RepositoryLifecycle {
    public record Update(String owner,String name,String cloneUrl,String defaultBranch,String type,String team,Long expectedRevision) {}
    private record Snapshot(RepositoryRecord repository,long revision,boolean desiredEnabled,boolean enabled) {}
    private record Work(UUID id,UUID repository,long target,RepositoryRecord previous,boolean reset,UUID token) {}
    private record HookWork(UUID id,UUID repository,RepositoryRecord previous,long hook,String callback,Long providerId,UUID token) {}
    private final JdbcTemplate jdbc;private final CodeContextProperties properties;
    private final GitRepositoryManager git;private final IndexingService indexing;private final RepositoryOnboarding onboarding;
    private final RepositoryIndexLeaseManager leases;private final TransactionTemplate transactions;private final JsonMapper json;
    private final GitHubWebhookClient webhooks;
    private final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"repository-lifecycle");thread.setDaemon(true);return thread;});
    public RepositoryLifecycle(JdbcTemplate jdbc,CodeContextProperties properties,GitRepositoryManager git,IndexingService indexing,
            RepositoryOnboarding onboarding,RepositoryIndexLeaseManager leases,PlatformTransactionManager tx,JsonMapper json,GitHubWebhookClient webhooks) {
        this.jdbc=jdbc;this.properties=properties;this.git=git;this.indexing=indexing;this.onboarding=onboarding;
        this.leases=leases;this.transactions=new TransactionTemplate(tx);this.json=json;
        this.webhooks=webhooks;
    }

    public Map<String,Object> update(UUID id,String team,Update request) {
        Snapshot before=owned(id,team,false);RepositoryRecord old=before.repository();
        String owner=value(request.owner(),old.owner()),name=value(request.name(),old.name());
        requireComponent(owner);requireComponent(name);
        String url=value(request.cloneUrl(),old.cloneUrl()),branch=value(request.defaultBranch(),old.defaultBranch());
        GitRepositoryManager.requireProviderIdentity(url,owner,name);
        String type=value(request.type(),old.repositoryType()).toUpperCase(java.util.Locale.ROOT);
        if(!List.of("SERVICE","LIBRARY").contains(type))throw new IllegalArgumentException("unsupported repository type");
        String nextTeam=value(request.team(),old.team());
        if(!ApiKeyRoster.parse(properties.adminApiKeys()).teams().contains(nextTeam))
            throw new IllegalArgumentException("target owning team requires a configured administrator key");
        if(!url.equals(old.cloneUrl())||!branch.equals(old.defaultBranch())) {
            git.validateRegistrationUrl(url);branch=git.verifyAccessAndBranch(url,request.defaultBranch()==null&&!url.equals(old.cloneUrl())?null:branch);
        }
        Path mirror=root().resolve(owner).resolve(name);
        var target=new RepositoryRecord(id,owner,name,url,nextTeam,branch,type,mirror.toString());
        return transactions.execute(status->{
            var current=owned(id,team,true);
            if(request.expectedRevision()!=null&&request.expectedRevision()!=current.revision())throw conflict("repository revision changed; reload before updating");
            if(current.repository().equals(target))return status(id,team);
            if(current.revision()!=before.revision())throw conflict("repository configuration changed during validation");
            if(!current.desiredEnabled())throw conflict("repository is offboarded; explicitly re-onboard before updating it");
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(lower(?),0))",Object.class,owner+"/"+name);
            requireMirrorAvailable(mirror,id);
            requireIdentityAvailable(owner,name,id);
            long revision=current.revision()+1;
            queuePreparation(old,target,revision);
            // Disabled visibility is committed before any destructive cleanup. In-flight old workers
            // can finish extraction, but both the application and V16 trigger reject their publication.
            jdbc.update("UPDATE repositories SET owner=?,name=?,clone_url=?,team=?,default_branch=?,repository_type=?,mirror_path=?,configuration_revision=?,enabled=false WHERE id=?",
                    owner,name,url,nextTeam,target.defaultBranch(),type,mirror.toString(),revision,id);
            if(!old.team().equals(nextTeam))jdbc.update("DELETE FROM team_repository_access WHERE repository_id=?",id);
            jdbc.update("UPDATE index_jobs SET state='FAILED',error='repository configuration changed',updated_at=now() WHERE repository_id=? AND state IN ('QUEUED','RUNNING')",id);
            UUID job=indexing.enqueueConfigurationChange(id,revision);onboarding.trackTransition(target,job);
            return status(id,nextTeam);
        });
    }
    public void requireSettled(UUID repository) {
        Integer pending=jdbc.queryForObject("SELECT count(*) FROM repository_lifecycle_work WHERE repository_id=? AND state<>'DONE'",Integer.class,repository);
        if(pending!=null&&pending>0)throw conflict("repository lifecycle preparation is pending; use its status/retry endpoint");
    }
    public void reactivate(RepositoryRecord previous,RepositoryRecord target) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("reactivation requires the registration transaction");
        long revision=jdbc.queryForObject("UPDATE repositories SET configuration_revision=configuration_revision+1,enabled=false,desired_enabled=true WHERE id=? RETURNING configuration_revision",Long.class,target.id());
        queuePreparation(previous,target,revision);
        jdbc.update("UPDATE index_jobs SET state='FAILED',error='repository reactivated with a new configuration revision',updated_at=now() WHERE repository_id=? AND state IN ('QUEUED','RUNNING')",target.id());
        var job=indexing.enqueueConfigurationChange(target.id(),revision);onboarding.trackTransition(target,job);
    }
    private void queuePreparation(RepositoryRecord previous,RepositoryRecord target,long revision) {
        boolean reset=!previous.key().equals(target.key())||!previous.cloneUrl().equals(target.cloneUrl())||!previous.defaultBranch().equals(target.defaultBranch());
        var hooks=jdbc.queryForList("SELECT webhook_id,webhook_callback_url,provider_repository_id FROM repository_onboarding WHERE repository_id=? FOR UPDATE",target.id());
        Object hook=hooks.isEmpty()?null:hooks.getFirst().get("webhook_id");
        Object callback=hooks.isEmpty()?null:hooks.getFirst().get("webhook_callback_url");
        Object providerId=hooks.isEmpty()?null:hooks.getFirst().get("provider_repository_id");
        boolean retire=hook!=null&&(!previous.key().equals(target.key())||!previous.cloneUrl().equals(target.cloneUrl()));
        if(retire&&callback==null) {
            try {callback=webhooks.expectedRepositoryCallback(previous);}
            catch(IllegalArgumentException invalid){callback=null; /* persisted BLOCKED state below makes the missing provenance explicit */}
        }
        String retirement=retire?webhooks.repositoryManagementConfigured()&&callback!=null?"QUEUED":"BLOCKED":"NOT_APPLICABLE";
        jdbc.update("""
            INSERT INTO repository_lifecycle_work(repository_id,target_revision,repository_root,previous_repository,reset_mirror,
              previous_webhook_id,previous_webhook_callback,previous_provider_repository_id,webhook_retirement_state,webhook_retirement_error)
            VALUES(?,?,?,?::jsonb,?,?,?,?,?,?)
            """,target.id(),revision,root().toString(),json.writeValueAsString(previous),reset,retire?hook:null,retire?callback:null,retire?providerId:null,
                retirement,"BLOCKED".equals(retirement)?"Supply approved repository-webhook management and confirm the previous callback before retirement.":null);
    }
    public Map<String,Object> status(UUID id,String team) {
        var repository=owned(id,team,false);
        var work=jdbc.queryForList("SELECT id,target_revision,state,attempt,error,webhook_retirement_state,webhook_retirement_outcome,webhook_retirement_error,created_at,updated_at FROM repository_lifecycle_work WHERE repository_id=? ORDER BY target_revision DESC LIMIT 20",id);
        var jobs=jdbc.queryForList("SELECT j.id,j.state,j.requested_commit_sha,j.commit_sha,j.generation_id,j.error FROM index_jobs j JOIN repository_onboarding o ON o.initial_job_id=j.id WHERE o.repository_id=?",id);
        return Map.of("repository",repository.repository(),"revision",repository.revision(),"desiredEnabled",repository.desiredEnabled(),"enabled",repository.enabled(),
                "indexJob",jobs.isEmpty()?Map.of():jobs.getFirst(),"transitions",work);
    }
    public void retry(UUID id,String team) {
        transactions.executeWithoutResult(status->{
            owned(id,team,true);
            jdbc.update("UPDATE repository_lifecycle_work SET state='QUEUED',attempt=0,error=NULL,available_at=now(),claim_token=NULL,claim_until=NULL,updated_at=now() WHERE repository_id=? AND state='FAILED'",id);
            if(webhooks.repositoryManagementConfigured())jdbc.update("UPDATE repository_lifecycle_work SET webhook_retirement_state='QUEUED',webhook_retirement_error=NULL,attempt=0,available_at=now() WHERE repository_id=? AND previous_webhook_callback IS NOT NULL AND webhook_retirement_state IN ('FAILED','BLOCKED')",id);
        });
    }
    public void requireMirrorAvailable(Path path,UUID except) {
        Integer pending=jdbc.queryForObject("SELECT count(*) FROM repository_lifecycle_work WHERE state<>'DONE' AND lower(previous_repository->>'mirrorPath')=lower(?) AND (?::uuid IS NULL OR repository_id<>?)",Integer.class,path.toString(),except,except);
        if(pending!=null&&pending>0)throw conflict("repository path cleanup is pending; retry after lifecycle recovery");
    }
    public void requireIdentityAvailable(String owner,String name,UUID except) {
        Integer claimed=jdbc.queryForObject("SELECT count(*) FROM repositories WHERE lower(owner)=lower(?) AND lower(name)=lower(?) AND (?::uuid IS NULL OR id<>?)",Integer.class,owner,name,except,except);
        if(claimed!=null&&claimed>0)throw conflict("target repository identity is already registered, including case aliases");
    }
    @Scheduled(fixedDelayString="${LIFECYCLE_POLL_MS:3000}") public void poll() {
        if(!busy.compareAndSet(false,true))return;
        try {worker.execute(()->{try{processOne();retireOne();}finally{busy.set(false);}});}catch(java.util.concurrent.RejectedExecutionException stopped){busy.set(false);}
    }
    public boolean retireOne() {
        if(!webhooks.repositoryManagementConfigured())return false;
        UUID token=UUID.randomUUID();
        var work=jdbc.query("""
            WITH candidate AS (SELECT id FROM repository_lifecycle_work WHERE repository_root=? AND state='DONE'
              AND (webhook_retirement_state='QUEUED' AND available_at<=now() OR webhook_retirement_state='RUNNING' AND claim_until<now())
              ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1)
            UPDATE repository_lifecycle_work w SET webhook_retirement_state='RUNNING',claim_token=?,claim_until=now()+interval '10 minutes'
            WHERE w.id=(SELECT id FROM candidate) RETURNING w.id,w.repository_id,w.previous_repository::text,w.previous_webhook_id,w.previous_webhook_callback,w.previous_provider_repository_id
            """,(rs,n)->new HookWork(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),json.readValue(rs.getString(3),RepositoryRecord.class),rs.getLong(4),rs.getString(5),(Long)rs.getObject(6),token),root().toString(),token);
        if(work.isEmpty())return false;
        var item=work.getFirst();RepositoryIndexLeaseManager.Lease lease=null;
        try {
            if(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM repositories WHERE id=?)",Boolean.class,item.repository()))) {
                var claim=leases.tryAcquire(item.repository(),item.id());
                if(claim.isEmpty()) {
                    jdbc.update("UPDATE repository_lifecycle_work SET webhook_retirement_state='QUEUED',claim_token=NULL,claim_until=NULL,available_at=now()+interval '3 seconds' WHERE id=? AND claim_token=?",item.id(),item.token());return true;
                }lease=claim.get();
            }
            String outcome;
            var active=jdbc.queryForList("SELECT webhook_id,webhook_callback_url,webhook_state,webhook_mode,webhook_configuration_hash FROM repository_onboarding WHERE repository_id=?",item.repository());
            var currentRepository=record(item.repository());
            if(!active.isEmpty()&&Long.valueOf(item.hook()).equals(active.getFirst().get("webhook_id"))
                    &&"READY".equals(active.getFirst().get("webhook_state"))&&"REPOSITORY".equals(active.getFirst().get("webhook_mode"))
                    &&active.getFirst().get("webhook_callback_url") instanceof String callback&&!callback.isBlank()&&!item.callback().equals(callback)
                    &&currentRepository!=null&&java.util.Objects.equals(java.net.URI.create(currentRepository.cloneUrl()).getHost(),java.net.URI.create(item.previous().cloneUrl()).getHost())
                    &&webhooks.configurationHash(currentRepository).equals(active.getFirst().get("webhook_configuration_hash")))outcome="REUSED";
            else {
                try {outcome=webhooks.retireOwnedHook(item.previous(),item.hook(),item.callback(),item.providerId(),true).name();}
                catch(GitHubWebhookClient.ProviderFailure | GitHubWebhookClient.ProviderIdentityChanged moved) {
                    if(moved instanceof GitHubWebhookClient.ProviderFailure provider&&!List.of(301,302,404).contains(provider.status))throw moved;
                    var current=record(item.repository());
                    if(current==null||current.key().equals(item.previous().key()))throw moved;
                    // A renamed provider can expose the same owned hook under its new namespace.
                    // Absence under an unrelated new repository cannot prove old-hook deletion.
                    outcome=webhooks.retireOwnedHook(current,item.hook(),item.callback(),item.providerId(),false).name();
                }
            }
            var held=lease;String completed=outcome;
            transactions.executeWithoutResult(status->{
                if(held!=null)held.fenceTransaction();
                jdbc.update("UPDATE repository_lifecycle_work SET webhook_retirement_state='DONE',webhook_retirement_outcome=?,webhook_retirement_error=NULL,claim_token=NULL,claim_until=NULL,updated_at=now() WHERE id=? AND claim_token=?",completed,item.id(),item.token());
            });
        }catch(Exception failure) {
            boolean permission=failure instanceof GitHubWebhookClient.ProviderFailure provider&&List.of(301,302,401,403,404).contains(provider.status)
                    ||failure instanceof IllegalStateException;
            jdbc.update("UPDATE repository_lifecycle_work SET webhook_retirement_state=CASE WHEN ? THEN 'BLOCKED' WHEN attempt>=4 THEN 'FAILED' ELSE 'QUEUED' END,attempt=attempt+1,webhook_retirement_error=?,available_at=now()+interval '1 minute',claim_token=NULL,claim_until=NULL,updated_at=now() WHERE id=? AND claim_token=?",
                    permission,"Owned webhook retirement needs provider authorization/ownership confirmation ("+failure.getClass().getSimpleName()+").",item.id(),item.token());
        }finally{if(lease!=null)lease.close();}
        return true;
    }
    private RepositoryRecord record(UUID id) {
        return jdbc.query("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path FROM repositories WHERE id=?",(rs,n)->new RepositoryRecord(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),id).stream().findFirst().orElse(null);
    }
    public boolean processOne() {
        UUID token=UUID.randomUUID();
        var work=jdbc.query("""
            WITH candidate AS (
              SELECT w.id FROM repository_lifecycle_work w WHERE w.repository_root=? AND (
                w.state='QUEUED' AND w.available_at<=now() OR w.state='RUNNING' AND w.claim_until<now())
                AND NOT EXISTS(SELECT 1 FROM repository_lifecycle_work earlier WHERE earlier.repository_id=w.repository_id
                               AND earlier.target_revision<w.target_revision AND earlier.state<>'DONE')
              ORDER BY w.available_at,w.created_at FOR UPDATE SKIP LOCKED LIMIT 1)
            UPDATE repository_lifecycle_work w SET state='RUNNING',claim_token=?,claim_until=now()+interval '10 minutes',updated_at=now()
            WHERE w.id=(SELECT id FROM candidate) RETURNING w.id,w.repository_id,w.target_revision,w.previous_repository::text,w.reset_mirror
            """,(rs,n)->new Work(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),json.readValue(rs.getString(4),RepositoryRecord.class),rs.getBoolean(5),token),root().toString(),token);
        if(work.isEmpty())return false;
        var item=work.getFirst();RepositoryIndexLeaseManager.Lease lease=null;
        try {
            boolean exists=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM repositories WHERE id=?)",Boolean.class,item.repository()));
            if(exists) {
                var claimed=leases.tryAcquire(item.repository(),item.id());
                if(claimed.isEmpty()){defer(item);return true;}lease=claimed.get();
            }
            if(item.reset())removeOldMirror(item);
            var held=lease;
            transactions.executeWithoutResult(status->{
                if(held!=null)held.fenceTransaction();
                var claimed=jdbc.queryForList("SELECT id FROM repository_lifecycle_work WHERE id=? AND claim_token=? AND state='RUNNING' FOR UPDATE",item.id(),item.token());
                if(claimed.isEmpty())throw new IllegalStateException("lifecycle ownership changed");
                jdbc.queryForList("SELECT id FROM repositories WHERE id=? FOR UPDATE",item.repository());
                var previous=item.previous();
                jdbc.update("""
                    INSERT INTO generation_cleanup_outbox(generation_id,repository_id,owner,name)
                    SELECT id,repository_id,?,? FROM index_generations WHERE repository_id=? AND repository_revision=?
                    ON CONFLICT DO NOTHING
                    """,previous.owner(),previous.name(),item.repository(),item.target()-1);
                jdbc.update("UPDATE index_generations SET published=false WHERE repository_id=? AND repository_revision<?",item.repository(),item.target());
                jdbc.update("UPDATE repository_lifecycle_work SET state='DONE',error=NULL,claim_token=NULL,claim_until=NULL,updated_at=now() WHERE id=? AND claim_token=?",item.id(),item.token());
                jdbc.update("""
                    UPDATE repositories r SET enabled=true WHERE r.id=? AND r.desired_enabled
                    AND NOT EXISTS(SELECT 1 FROM repository_lifecycle_work pending WHERE pending.repository_id=r.id AND pending.state<>'DONE')
                    """,item.repository());
                jdbc.update("INSERT INTO team_repository_access(team,repository_id) SELECT team,id FROM repositories WHERE id=? AND enabled ON CONFLICT DO NOTHING",item.repository());
            });
        }catch(Exception failure) {
            jdbc.update("UPDATE repository_lifecycle_work SET state=CASE WHEN attempt>=4 THEN 'FAILED' ELSE 'QUEUED' END,attempt=attempt+1,error=?,available_at=now()+interval '10 seconds',claim_token=NULL,claim_until=NULL,updated_at=now() WHERE id=? AND claim_token=?",
                    "Lifecycle preparation failed ("+failure.getClass().getSimpleName()+"); verify mirror ownership and storage, then retry.",item.id(),item.token());
        }finally{if(lease!=null)lease.close();}
        return true;
    }
    private void removeOldMirror(Work work)throws Exception {
        var old=work.previous();Path expected=root().resolve(old.owner()).resolve(old.name());
        if(!expected.equals(Path.of(old.mirrorPath()).toAbsolutePath().normalize()))throw new IllegalStateException("legacy mirror path needs explicit operator reconciliation");
        requireMirrorAvailable(expected,work.repository());
        Integer claimed=jdbc.queryForObject("SELECT count(*) FROM repositories WHERE lower(mirror_path)=lower(?) AND id<>?",Integer.class,expected.toString(),work.repository());
        if(claimed!=null&&claimed>0)throw new IllegalStateException("old mirror path is owned by another repository");
        git.deleteMirrorIfOriginMatches(old);
    }
    private void defer(Work work){jdbc.update("UPDATE repository_lifecycle_work SET state='QUEUED',available_at=now()+interval '3 seconds',claim_token=NULL,claim_until=NULL WHERE id=? AND claim_token=?",work.id(),work.token());}
    private Snapshot owned(UUID id,String team,boolean lock) {
        return jdbc.query("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path,configuration_revision,desired_enabled,enabled FROM repositories WHERE id=? AND team=?"+(lock?" FOR UPDATE":""),
                (rs,n)->new Snapshot(new RepositoryRecord(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),rs.getLong(9),rs.getBoolean(10),rs.getBoolean(11)),id,team)
                .stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"repository not found or access denied"));
    }
    private Path root(){return Path.of(properties.repositoryRoot()).toAbsolutePath().normalize();}
    private static String value(String proposed,String existing){if(proposed==null)return existing;if(proposed.isBlank())throw new IllegalArgumentException("blank configuration value");return proposed;}
    private static void requireComponent(String value){if(!value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,99}"))throw new IllegalArgumentException("unsafe repository identity");}
    private static ResponseStatusException conflict(String message){return new ResponseStatusException(HttpStatus.CONFLICT,message);}
    @jakarta.annotation.PreDestroy public void stop(){worker.shutdownNow();}
}
