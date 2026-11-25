package com.wynk.codecontext.index;

import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.repo.RepositoryStore;
import com.wynk.codecontext.search.ZoektIndexer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL: retention eligibility, cascade deletion, durable cleanup retry and fencing. */
class GenerationRetentionPostgresIT {
    @Test void protectsEvidenceAndRecoversCleanupWithoutAllowingStalePublication() throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        String user=System.getenv().getOrDefault("DATABASE_USER","code_context");
        String password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        assertTrue(admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"));
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        admin.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        String schema="retention_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);
        var jdbc=new JdbcTemplate(ds);
        var tx=new DataSourceTransactionManager(ds);
        var leases=new RepositoryIndexLeaseManager(jdbc);
        try {
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                    .placeholders(Map.of("vectorDimensions","1024")).load().migrate();
            UUID repo=jdbc.queryForObject("INSERT INTO repositories(owner,name,clone_url,team,mirror_path) VALUES('fixture','retention','','team','') RETURNING id",UUID.class);
            UUID obsolete=generation(jdbc,repo,1,false);
            UUID baseline=generation(jdbc,repo,2,false);
            UUID active=generation(jdbc,repo,3,true);
            var adapter=new CleanupAdapter();
            var gc=new GenerationRetentionService(jdbc,leases,adapter,tx,30,10);
            assertFalse(gc.collect(active,repo));
            assertFalse(gc.collect(baseline,repo));
            UUID running=jdbc.queryForObject("INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state) VALUES(?,'pending',?,'RUNNING') RETURNING id",UUID.class,repo,UUID.randomUUID().toString());
            assertFalse(gc.collect(obsolete,repo));
            jdbc.update("DELETE FROM index_jobs WHERE id=?",running);
            jdbc.update("INSERT INTO cross_repo_impact_analyses(team,origin_repository_id,before_generation_id,after_generation_id,after_commit,analysis_status,result) VALUES('team',?,?,?,'3','COMPLETE','{}')",repo,obsolete,active);
            assertFalse(gc.collect(obsolete,repo));
            jdbc.update("DELETE FROM cross_repo_impact_analyses");
            jdbc.update("INSERT INTO code_nodes(generation_id,node_type,symbol,file_path,start_line,end_line) VALUES(?,'METHOD','old','Old.java',1,1)",obsolete);
            jdbc.update("INSERT INTO code_chunks(generation_id,file_path,start_line,end_line,content_hash,content) VALUES(?,'Old.java',1,1,'hash','old')",obsolete);
            Long node=jdbc.queryForObject("SELECT id FROM code_nodes WHERE generation_id=?",Long.class,obsolete);
            jdbc.update("INSERT INTO code_edges(generation_id,source_node,target_node,edge_type,confidence) VALUES(?,?,?,'CALL',1)",obsolete,node,node);
            Long contract=jdbc.queryForObject("INSERT INTO contracts(generation_id,contract_type,canonical_key,role,file_path,line,evidence,confidence,content_hash) VALUES(?,'REST','rest:GET:/old','EXPORT','Old.java',1,'fixture',1,'hash') RETURNING id",Long.class,obsolete);
            Long consumer=jdbc.queryForObject("INSERT INTO contracts(generation_id,contract_type,canonical_key,role,file_path,line,evidence,confidence,content_hash) VALUES(?,'REST','rest:GET:/old','IMPORT','New.java',1,'fixture',1,'hash') RETURNING id",Long.class,active);
            jdbc.update("INSERT INTO contract_links(provider_contract,consumer_contract,confidence) VALUES(?,?,1)",contract,consumer);
            assertFalse(gc.collect(obsolete,repo));
            jdbc.update("DELETE FROM contract_links WHERE provider_contract=?",contract);
            Long symbol=jdbc.queryForObject("INSERT INTO semantic_symbols(repository_id,generation_id,commit_sha,symbol_key,declaring_type,signature,symbol_kind,resolution_status) VALUES(?,?,'1','Old#call()','Old','call()','METHOD','RESOLVED') RETURNING id",Long.class,repo,obsolete);
            Long call=jdbc.queryForObject("INSERT INTO semantic_call_sites(repository_id,generation_id,commit_sha,file_path,start_line,end_line,invocation,resolution_status,evidence,confidence,extractor_version,caller_symbol_id,target_symbol_id) VALUES(?,?,'1','Old.java',1,1,'call()','RESOLVED','fixture',1,'test',?,?) RETURNING id",Long.class,repo,obsolete,symbol,symbol);
            jdbc.update("INSERT INTO semantic_call_edges(repository_id,generation_id,call_site_id,caller_symbol_id,target_symbol_id,resolution_status,evidence,confidence) VALUES(?,?,?,?,?,'RESOLVED','fixture',1)",repo,obsolete,call,symbol,symbol);
            assertTrue(gc.collect(obsolete,repo));
            assertFalse(gc.collect(obsolete,repo));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=?",Integer.class,obsolete));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=?",Integer.class,obsolete));
            for(String table:java.util.List.of("code_edges","contracts","semantic_symbols","semantic_call_sites","semantic_call_edges"))
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE generation_id=?",Integer.class,obsolete),table);
            assertTrue(gc.cleanOne()); // external failure is durably retried
            assertEquals(1,jdbc.queryForObject("SELECT attempt FROM generation_cleanup_outbox WHERE generation_id=?",Integer.class,obsolete));
            jdbc.update("UPDATE generation_cleanup_outbox SET available_at=now()");
            adapter.fail=false;
            var restarted=new GenerationRetentionService(jdbc,leases,adapter,tx,30,10);
            assertTrue(restarted.cleanOne());
            assertEquals("DONE",jdbc.queryForObject("SELECT state FROM generation_cleanup_outbox WHERE generation_id=?",String.class,obsolete));
            assertFalse(restarted.cleanOne());

            var stale=leases.tryAcquire(repo,UUID.randomUUID()).orElseThrow();
            jdbc.update("UPDATE repository_index_leases SET lease_expires_at=now()-interval '1 second' WHERE repository_id=?",repo);
            try(var owner=leases.tryAcquire(repo,UUID.randomUUID()).orElseThrow()) {
                assertThrows(IllegalStateException.class,()->new TransactionTemplate(tx).execute(s->{stale.fenceTransaction(); return null;}));
                stale.close();
                owner.assertOwned();
                new TransactionTemplate(tx).execute(s->{owner.fenceTransaction(); return null;});
            }
            try(var held=leases.tryAcquire(repo,UUID.randomUUID()).orElseThrow()) {
                new TransactionTemplate(tx).execute(status->{
                    held.fenceTransaction();
                    jdbc.update("UPDATE repository_index_leases SET lease_expires_at=clock_timestamp()-interval '1 second' WHERE repository_id=?",repo);
                    var competing=java.util.concurrent.CompletableFuture.runAsync(()->
                        new TransactionTemplate(tx).execute(other->{
                            jdbc.execute("SET LOCAL lock_timeout='250ms'");
                            leases.tryAcquire(repo,UUID.randomUUID()).ifPresent(RepositoryIndexLeaseManager.Lease::close);
                            return null;
                        }));
                    var failure=assertThrows(java.util.concurrent.ExecutionException.class,
                            ()->competing.get(5,java.util.concurrent.TimeUnit.SECONDS));
                    assertInstanceOf(org.springframework.dao.DataAccessException.class,failure.getCause());
                    return null;
                });
                try(var takeover=leases.tryAcquire(repo,UUID.randomUUID()).orElseThrow()) {
                    takeover.assertOwned();
                }
            }
            UUID foreignRepo=jdbc.queryForObject("INSERT INTO repositories(owner,name,clone_url,team,mirror_path) VALUES('foreign','provider','','foreign-team','') RETURNING id",UUID.class);
            UUID foreignGeneration=generation(jdbc,foreignRepo,1,true);
            jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES('local-team',?),('foreign-team',?)",repo,foreignRepo);
            jdbc.update("INSERT INTO contracts(generation_id,contract_type,canonical_key,role,file_path,line,evidence,confidence,content_hash) VALUES(?,'REST','rest:GET:/old','EXPORT','Foreign.java',1,'fixture',1,'hash')",foreignGeneration);
            var relationships=new ConsumerContractIndexRefresher(jdbc,null,null);
            try {
                relationships.refreshGeneration(foreignGeneration);
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM contract_links",Integer.class),"matching identities alone must not establish cross-team relationships");
                jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES('local-team',?)",foreignRepo);
                relationships.refreshGeneration(foreignGeneration);
                assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM contract_links",Integer.class),"explicit shared access permits relationship binding");
                jdbc.update("DELETE FROM team_repository_access WHERE repository_id=? AND team='local-team'",foreignRepo);
                relationships.pruneSupersededRelationships();
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM contract_links",Integer.class),"revoked shared access must prune stored relationships");
            } finally { relationships.shutdown(); }
            // Disable and re-onboard retain the published evidence and the same repository identity.
            var store=new RepositoryStore(jdbc);
            store.disable(repo);
            assertTrue(jdbc.queryForObject("SELECT NOT enabled FROM repositories WHERE id=?",Boolean.class,repo));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM team_repository_access WHERE repository_id=?",Integer.class,repo));
            assertFalse(gc.collect(active,repo));
            assertThrows(AccessDeniedException.class,()->store.insert("fixture","retention","","foreign-team","main","SERVICE",""));
            var reonboarded=store.insert("fixture","retention","","team","main","SERVICE","/tmp/retention");
            assertEquals(repo,reonboarded.id(),"soft-disabled repositories retain identity for the owning team");
            jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES('team',?)",repo);
            assertFalse(gc.collect(baseline,repo));

            // Permanent Zoekt cleanup failure after ten attempts.
            UUID doomed=generation(jdbc,repo,4,false);
            jdbc.update("UPDATE index_generations SET created_at=now()-interval '60 days' WHERE id=?",doomed);
            adapter.fail=true;
            assertTrue(gc.collect(doomed,repo));
            for(int attempt=1;attempt<=10;attempt++) {
                jdbc.update("UPDATE generation_cleanup_outbox SET available_at=now() WHERE generation_id=?",doomed);
                assertTrue(gc.cleanOne());
                assertEquals(attempt,jdbc.queryForObject("SELECT attempt FROM generation_cleanup_outbox WHERE generation_id=?",Integer.class,doomed));
            }
            assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM generation_cleanup_outbox WHERE generation_id=?",String.class,doomed));
            jdbc.update("UPDATE generation_cleanup_outbox SET available_at=now() WHERE generation_id=?",doomed);
            assertFalse(gc.cleanOne(),"terminal FAILED outbox rows must not be claimed");

            // Concurrent lease fencing: a second collector cannot delete while the first holds the fence.
            UUID contended=generation(jdbc,repo,5,false);
            jdbc.update("UPDATE index_generations SET created_at=now()-interval '60 days' WHERE id=?",contended);
            try(var held=leases.tryAcquire(repo,UUID.randomUUID()).orElseThrow()) {
                new TransactionTemplate(tx).execute(status->{
                    held.fenceTransaction();
                    var blocked=java.util.concurrent.CompletableFuture.supplyAsync(()->gc.collect(contended,repo));
                    assertFalse(assertTimeoutPreemptively(java.time.Duration.ofSeconds(3),blocked::join),
                            "collector without the lease must not delete while another owner fences the repository");
                    return null;
                });
            }
            assertTrue(gc.collect(contended,repo));
            adapter.fail=false;
            jdbc.update("UPDATE generation_cleanup_outbox SET available_at=now()+interval '1 hour' WHERE state='QUEUED'");
            jdbc.update("UPDATE generation_cleanup_outbox SET available_at=now() WHERE generation_id=?",contended);
            assertTrue(gc.cleanOne());
            assertEquals("DONE",jdbc.queryForObject("SELECT state FROM generation_cleanup_outbox WHERE generation_id=?",String.class,contended));

            // Orphaned Zoekt shard reconciliation enqueues missing PostgreSQL generations.
            UUID orphan=UUID.randomUUID();
            adapter.orphans=java.util.List.of(new ZoektIndexer.IndexedGeneration("fixture","retention",orphan));
            assertEquals(1,gc.reconcileOrphanedShards());
            assertEquals(0,gc.reconcileOrphanedShards(),"already tracked orphans must not duplicate outbox rows");
            assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM generation_cleanup_outbox WHERE generation_id=?",String.class,orphan));
            jdbc.update("UPDATE generation_cleanup_outbox SET available_at=now()+interval '1 hour' WHERE generation_id=?",orphan);

            // Authorized hard purge removes PostgreSQL evidence and queues Zoekt cleanup for every generation.
            jdbc.update("INSERT INTO code_chunks(generation_id,file_path,start_line,end_line,content_hash,content) VALUES(?,'Active.java',1,1,'hash','active')",active);
            jdbc.update("INSERT INTO cross_repo_impact_analyses(team,origin_repository_id,before_generation_id,after_generation_id,after_commit,analysis_status,result) VALUES('team',?,?,?,'3','COMPLETE','{}')",repo,baseline,active);
            Path mirror=Files.createTempDirectory("purge-mirror-");
            Files.writeString(mirror.resolve("README"),"mirror");
            jdbc.update("UPDATE repositories SET mirror_path=? WHERE id=?",mirror.toString(),repo);
            Integer queued=new TransactionTemplate(tx).execute(status->store.purge(repo));
            assertNotNull(queued);
            assertTrue(queued>=2);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM repositories WHERE id=?",Integer.class,repo));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=?",Integer.class,repo));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_chunks",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM cross_repo_impact_analyses",Integer.class));
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM generation_cleanup_outbox WHERE owner='fixture' AND name='retention' AND state='QUEUED'",Integer.class)>=2);
            // After hard purge any administrator team may reclaim the identity.
            var reclaimed=store.insert("fixture","retention","","foreign-team","main","SERVICE","/tmp/reclaim");
            assertNotEquals(repo,reclaimed.id());
        } finally {
            leases.shutdown();
            admin.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
    private UUID generation(JdbcTemplate jdbc,UUID repo,int number,boolean published) {
        String sha=String.valueOf(number);
        UUID id=jdbc.queryForObject("INSERT INTO index_generations(repository_id,commit_sha,generation,published,created_at) VALUES(?,?,?,?,now()-interval '60 days') RETURNING id",UUID.class,repo,sha,number,published);
        jdbc.update("INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state) VALUES(?,?,?,'SUCCEEDED')",repo,sha,UUID.randomUUID().toString());
        return id;
    }
    private static final class CleanupAdapter extends ZoektIndexer {
        boolean fail=true;
        java.util.List<IndexedGeneration> orphans=java.util.List.of();
        CleanupAdapter(){super(null,null);}
        @Override public void removeGeneration(RepositoryRecord repo,UUID generation) throws Exception {
            if(fail) throw new java.io.IOException("simulated unavailable Zoekt");
        }
        @Override public java.util.List<IndexedGeneration> listIndexedGenerations() { return orphans; }
        @Override public void removeRepositoryMetadata(UUID repositoryId) {}
    }
}
