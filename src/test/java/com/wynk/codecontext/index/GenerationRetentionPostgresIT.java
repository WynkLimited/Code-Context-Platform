package com.wynk.codecontext.index;

import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.search.ZoektIndexer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
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
            jdbc.update("UPDATE repositories SET enabled=false WHERE id=?",repo);
            assertFalse(gc.collect(active,repo));
            jdbc.update("UPDATE repositories SET enabled=true WHERE id=?",repo);
            assertFalse(gc.collect(baseline,repo));
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
        CleanupAdapter(){super(null,null);}
        @Override public void removeGeneration(RepositoryRecord repo,UUID generation) throws Exception {
            if(fail) throw new java.io.IOException("simulated unavailable Zoekt");
        }
    }
}
