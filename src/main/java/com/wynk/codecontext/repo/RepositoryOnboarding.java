package com.wynk.codecontext.repo;

import com.wynk.codecontext.index.IndexingService;
import com.wynk.codecontext.model.RepositoryRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.wynk.codecontext.index.RepositoryIndexLeaseManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;

/** Registration and the initial job are committed together; existing durable workers recover it. */
@Service
public final class RepositoryOnboarding {
    private final JdbcTemplate jdbc;
    private final IndexingService indexing;
    private final GitHubWebhookClient webhooks;
    private final RepositoryStore repositories;
    private final RepositoryIndexLeaseManager leases;
    private final TransactionTemplate transactions;
    private final java.util.concurrent.atomic.AtomicBoolean busy=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.ExecutorService worker=java.util.concurrent.Executors.newSingleThreadExecutor(task->{
        Thread thread=new Thread(task,"repository-webhook-reconcile");thread.setDaemon(true);return thread;
    });
    public RepositoryOnboarding(JdbcTemplate jdbc, IndexingService indexing,GitHubWebhookClient webhooks,
            RepositoryStore repositories,RepositoryIndexLeaseManager leases,PlatformTransactionManager tx) {
        this.jdbc=jdbc;this.indexing=indexing;this.webhooks=webhooks;this.repositories=repositories;
        this.leases=leases;this.transactions=new TransactionTemplate(tx);
    }

    public void schedule(RepositoryRecord repository) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("onboarding requires the registration transaction");
        boolean local=repository.cloneUrl().startsWith("file:");
        int inserted=jdbc.update("INSERT INTO repository_onboarding(repository_id,webhook_state,webhook_error,webhook_mode) VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                repository.id(),local?"NOT_APPLICABLE":webhooks.configured()?"QUEUED":"BLOCKED",
                local||webhooks.configured()?null:"Configure authorized GitHub webhook management or a verified GitHub App integration.",local?"NONE":webhooks.mode());
        if(inserted==0)return;
        var job=indexing.enqueue(repository.owner(),repository.name(),"HEAD","onboarding:"+repository.id());
        jdbc.update("UPDATE repository_onboarding SET initial_job_id=? WHERE repository_id=?",job,repository.id());
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelayString="${ONBOARDING_POLL_MS:5000}")
    public void poll() {
        if(!webhooks.configured()||!busy.compareAndSet(false,true))return;
        try {worker.execute(()->{try{reconcileOne();}finally{busy.set(false);}});}
        catch(java.util.concurrent.RejectedExecutionException stopped){busy.set(false);}
    }

    /** Claim tokens fence late workers. Provider reconciliation discovers an already-created hook after a crash. */
    public boolean reconcileOne() {
        if(!webhooks.configured())return false;
        var token=java.util.UUID.randomUUID();
        var candidates=jdbc.query("""
            WITH candidate AS (
              SELECT o.repository_id FROM repository_onboarding o JOIN repositories r ON r.id=o.repository_id
              WHERE r.enabled AND NOT r.clone_url LIKE 'file:%' AND (
                o.webhook_state='BLOCKED' OR o.webhook_state IN ('QUEUED','READY') AND o.webhook_available_at<=now()
                OR o.webhook_state='RUNNING' AND o.webhook_claim_until<now())
              ORDER BY o.webhook_available_at,o.repository_id FOR UPDATE OF o SKIP LOCKED LIMIT 1)
            UPDATE repository_onboarding o SET webhook_state='RUNNING',webhook_claim=?,
              webhook_claim_until=now()+interval '10 minutes',updated_at=now()
            WHERE o.repository_id=(SELECT repository_id FROM candidate) RETURNING o.repository_id
            """,(rs,n)->rs.getObject(1,java.util.UUID.class),token);
        if(candidates.isEmpty())return false;
        var id=candidates.getFirst();
        var claim=leases.tryAcquire(id,token);
        if(claim.isEmpty()) {
            jdbc.update("UPDATE repository_onboarding SET webhook_state='QUEUED',webhook_available_at=now()+interval '5 seconds',webhook_claim=NULL,webhook_claim_until=NULL WHERE repository_id=? AND webhook_claim=?",id,token);
            return true;
        }
        try(var lease=claim.get()) {
            transactions.executeWithoutResult(status->{
                // Serialize provider side effects with configuration changes. Keep the lease row
                // unlocked during HTTP so its independent heartbeat can continue renewing it.
                var repository=jdbc.query("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path FROM repositories WHERE id=? AND enabled FOR SHARE",
                        (rs,n)->new RepositoryRecord(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),id)
                        .stream().findFirst().orElseThrow();
                var claims=jdbc.queryForList("SELECT repository_id FROM repository_onboarding WHERE repository_id=? AND webhook_claim=?",id,token);
                if(claims.isEmpty())return;
                GitHubWebhookClient.Hook hook;
                try {hook=webhooks.reconcile(repository);}
                catch(Exception failure){throw new ReconciliationFailure(failure);}
                String configuration=webhooks.configurationHash(repository);
                lease.fenceTransaction();
                int changed=jdbc.update("UPDATE repository_onboarding SET webhook_state='READY',webhook_id=?,webhook_mode=?,webhook_callback_url=?,provider_repository_id=?,webhook_verified_at=CASE WHEN webhook_configuration_hash IS DISTINCT FROM ? OR webhook_verified_at IS NULL THEN now() ELSE webhook_verified_at END,webhook_configuration_hash=?,webhook_attempt=0,webhook_error=NULL,webhook_claim=NULL,webhook_claim_until=NULL,webhook_available_at=now()+interval '1 hour',updated_at=now() WHERE repository_id=? AND webhook_claim=?",hook.repositoryHookId(),hook.mode(),hook.callbackUrl(),hook.providerRepositoryId(),configuration,configuration,id,token);
                if(changed==1)indexing.enqueue(repository.owner(),repository.name(),"HEAD","webhook-catchup:"+repository.id()+":"+configuration);
            });
        }catch(Exception failure) {
            // Persist only the exception category, never provider response bodies or credentials.
            var category=failure instanceof ReconciliationFailure?failure.getCause():failure;
            jdbc.update("UPDATE repository_onboarding SET webhook_state=CASE WHEN webhook_attempt>=4 THEN 'FAILED' ELSE 'QUEUED' END,webhook_attempt=webhook_attempt+1,webhook_error=?,webhook_available_at=now()+interval '1 minute',webhook_claim=NULL,webhook_claim_until=NULL,updated_at=now() WHERE repository_id=? AND webhook_claim=?",
                    "Webhook reconciliation failed ("+category.getClass().getSimpleName()+"); verify provider permissions, callback URL and connectivity, then retry.",id,token);
        }
        return true;
    }
    private static final class ReconciliationFailure extends RuntimeException {
        ReconciliationFailure(Exception cause){super("Provider reconciliation failed",cause);}
    }

    public void retry(RepositoryRecord repository) {
        transactions.executeWithoutResult(status->{
            jdbc.queryForList("SELECT repository_id FROM repository_onboarding WHERE repository_id=? FOR UPDATE",repository.id());
            var states=jdbc.queryForList("SELECT j.state FROM repository_onboarding o LEFT JOIN index_jobs j ON j.id=o.initial_job_id WHERE o.repository_id=?",repository.id());
            if(states.isEmpty()){schedule(repository);return;}
            if(states.getFirst().get("state")==null||"FAILED".equals(states.getFirst().get("state"))) {
                var job=indexing.enqueue(repository.owner(),repository.name(),"HEAD","onboarding-retry:"+java.util.UUID.randomUUID());
                jdbc.update("UPDATE repository_onboarding SET initial_job_id=?,updated_at=now() WHERE repository_id=?",job,repository.id());
            }
            jdbc.update("UPDATE repository_onboarding SET webhook_state=?,webhook_attempt=0,webhook_available_at=now(),webhook_error=NULL,updated_at=now() WHERE repository_id=? AND webhook_state IN ('FAILED','BLOCKED','READY')",
                    webhooks.configured()?"QUEUED":"BLOCKED",repository.id());
        });
    }
    public void trackTransition(RepositoryRecord repository,UUID job) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("configuration transition requires a transaction");
        boolean local=repository.cloneUrl().startsWith("file:");
        jdbc.update("""
            INSERT INTO repository_onboarding(repository_id,initial_job_id,webhook_state,webhook_mode)
            VALUES(?,?,?,?) ON CONFLICT(repository_id) DO UPDATE SET initial_job_id=excluded.initial_job_id,
              webhook_state=excluded.webhook_state,webhook_mode=excluded.webhook_mode,webhook_attempt=0,
              webhook_available_at=now(),webhook_error=NULL,updated_at=now()
            """,repository.id(),job,local?"NOT_APPLICABLE":webhooks.configured()?"QUEUED":"BLOCKED",local?"NONE":webhooks.mode());
    }
    @jakarta.annotation.PreDestroy public void stop(){worker.shutdownNow();}

    public Map<String,Object> status(RepositoryRecord repository) {
        var rows=jdbc.queryForList("""
            SELECT o.repository_id,o.initial_job_id,coalesce(j.state,'MISSING') AS indexing_state,
              j.generation_id,j.error AS indexing_error,o.webhook_state,o.webhook_error,o.webhook_mode,o.webhook_verified_at,
              EXISTS(SELECT 1 FROM webhook_deliveries d WHERE d.repository_id=o.repository_id AND d.signing_key_fingerprint=? AND d.received_at>=o.webhook_verified_at) AS webhook_delivery_verified,
              o.webhook_configuration_hash=? AND o.webhook_mode=? AS webhook_configuration_current,
              o.created_at,o.updated_at
            FROM repository_onboarding o LEFT JOIN index_jobs j ON j.id=o.initial_job_id
            WHERE o.repository_id=?
            """,webhooks.signingKeyFingerprint(),webhooks.configurationHash(repository),webhooks.mode(),repository.id());
        if(rows.isEmpty())return Map.of("repository",repository.key(),"state","LEGACY_REGISTRATION",
                "action","Submit the unchanged registration to schedule automatic initial indexing.");
        var result=new java.util.LinkedHashMap<>(rows.getFirst());
        result.put("repository",repository.key());
        String job=String.valueOf(result.get("indexing_state"));
        String webhook=String.valueOf(result.get("webhook_state"));
        result.put("state","FAILED".equals(job)?"INDEXING_FAILED":!"SUCCEEDED".equals(job)?"INDEXING":
                !java.util.Set.of("READY","NOT_APPLICABLE").contains(webhook)?"WEBHOOK_ACTION_REQUIRED":"INDEXED");
        if("READY".equals(webhook)&&(!webhooks.configured()||!Boolean.TRUE.equals(result.get("webhook_configuration_current")))) {
            result.put("state","SUCCEEDED".equals(job)?"WEBHOOK_ACTION_REQUIRED":result.get("state"));
            result.put("action","Webhook configuration or management credentials changed; reconcile the current approved integration.");
        } else if("READY".equals(webhook)&&!Boolean.TRUE.equals(result.get("webhook_delivery_verified"))) {
            result.put("state","SUCCEEDED".equals(job)?"WEBHOOK_DELIVERY_UNVERIFIED":result.get("state"));
            result.put("action","Verify a genuine signed push delivery; provider configuration alone does not prove signing-key agreement.");
        } else if("BLOCKED".equals(webhook))result.put("action","Configure an approved central GitHub App or separately authorized repository webhook management.");
        return result;
    }
}
