package com.wynk.codecontext.repo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Signed delivery handling and cross-replica credential invalidation, never automatic ACL restoration. */
@Service
public final class GitHubAppAccess {
    private final JdbcTemplate jdbc;private final RepositoryStore repositories;
    private final TransactionTemplate transactions;private final String host;
    public GitHubAppAccess(JdbcTemplate jdbc,RepositoryStore repositories,PlatformTransactionManager tx,
            @Value("${GITHUB_API_BASE_URL:https://api.github.com}") String apiBase) {
        this.jdbc=jdbc;this.repositories=repositories;this.transactions=new TransactionTemplate(tx);
        String apiHost=URI.create(apiBase).getHost();
        if(apiHost==null)throw new IllegalArgumentException("GitHub API requires a host");
        this.host=("api.github.com".equalsIgnoreCase(apiHost)?"github.com":apiHost).toLowerCase(java.util.Locale.ROOT);
    }
    public long epoch(String providerHost,long installation) {
        var versions=jdbc.query("SELECT version FROM github_installation_epochs WHERE provider_host=? AND installation_id=?",(rs,n)->rs.getLong(1),providerHost,installation);
        return versions.isEmpty()?0:versions.getFirst();
    }
    /** Must share the transaction containing the corresponding delivery side effect. */
    public boolean claim(UUID delivery,String event,String hash) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("webhook receipt requires a transaction");
        int inserted=jdbc.update("INSERT INTO github_event_receipts(delivery_id,event,payload_sha256) VALUES(?,?,?) ON CONFLICT DO NOTHING",delivery,event,hash);
        if(inserted==1)return true;
        var previous=jdbc.queryForMap("SELECT event,payload_sha256 FROM github_event_receipts WHERE delivery_id=?",delivery);
        if(!event.equals(previous.get("event"))||!hash.equals(String.valueOf(previous.get("payload_sha256")).strip()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"delivery ID was already used for a different event or payload");
        return false;
    }
    public Map<String,Object> receive(UUID delivery,String event,String hash,JsonNode payload) {
        if(!List.of("installation","installation_repositories").contains(event))throw bad("unsupported installation event");
        if(payload==null||!payload.isObject())throw bad("installation payload must be an object");
        long installation=positiveId(payload.path("installation").path("id"));
        String action=payload.path("action").asText();
        boolean membership="installation_repositories".equals(event);
        if(!(membership?List.of("added","removed"):List.of("created","deleted","suspend","unsuspend","new_permissions_accepted")).contains(action))
            throw bad("unsupported installation action");
        List<Long> removed=new ArrayList<>();
        if(membership) {
            var list=payload.path("repositories_removed");
            if(!list.isArray()||list.size()>5000)throw bad("bounded repositories_removed array is required");
            for(var repository:list)removed.add(positiveId(repository.path("id")));
        }
        boolean revoke=membership?"removed".equals(action):List.of("deleted","suspend").contains(action);
        return transactions.execute(status->{
            if(!claim(delivery,event+":"+action,hash))return Map.of("accepted",false,"duplicate",true);
            // Legacy push receipts predate the common inbox; never reuse their delivery IDs.
            if(!jdbc.queryForList("SELECT delivery_id FROM webhook_deliveries WHERE delivery_id=?",delivery).isEmpty())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"delivery ID was already used for a push");
            jdbc.update("""
                INSERT INTO github_installation_epochs(provider_host,installation_id,last_delivery_id) VALUES(?,?,?)
                ON CONFLICT(provider_host,installation_id) DO UPDATE SET version=github_installation_epochs.version+1,
                  last_delivery_id=excluded.last_delivery_id,updated_at=now()
                """,host,installation,delivery);
            int affected=0;
            if(revoke) {
                var arguments=new ArrayList<Object>();arguments.add(host);arguments.add(installation);
                String filter="";
                if(membership&&!removed.isEmpty()) {
                    filter=" AND o.provider_repository_id IN ("+String.join(",",java.util.Collections.nCopies(removed.size(),"?"))+")";
                    arguments.addAll(removed);
                }
                // A legacy binding without installation provenance is suspended conservatively.
                // An empty removed list cannot prove which previously-all-selected repos remain allowed.
                var ids=jdbc.query("SELECT r.id FROM repositories r JOIN repository_onboarding o ON o.repository_id=r.id WHERE o.webhook_mode='APP' AND coalesce(o.provider_host,lower(substring(r.clone_url from '(?i)^https://([^/:]+)')))=? AND (o.provider_installation_id=? OR o.provider_installation_id IS NULL)"+filter+" ORDER BY r.id FOR UPDATE OF r",
                        (rs,n)->rs.getObject(1,UUID.class),arguments.toArray());
                for(UUID id:ids) {
                    repositories.disable(id);
                    jdbc.update("UPDATE repository_onboarding SET webhook_state='FAILED',webhook_error='GitHub App access revoked; explicit administrator revalidation is required.',webhook_claim=NULL,webhook_claim_until=NULL,updated_at=now() WHERE repository_id=?",id);
                    affected++;
                }
            }
            return Map.of("accepted",true,"event",event,"action",action,"repositoriesSuspended",affected);
        });
    }
    private static long positiveId(JsonNode value) {
        if(!value.isIntegralNumber()||!value.canConvertToLong()||value.asLong()<=0)throw bad("positive integral provider identity is required");
        return value.asLong();
    }
    private static ResponseStatusException bad(String message){return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
}
