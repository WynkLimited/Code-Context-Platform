package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.index.IndexingService;
import com.wynk.codecontext.index.RepositoryIndexLeaseManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL atomicity/fencing against a controlled provider exchange; NOT live GitHub certification. */
class RepositoryOnboardingPostgresIT {
    @Test void registrationRollsBackAtomicallyAndWebhookReconciliationRecoversAfterLostProviderResponse() throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        String user=System.getenv().getOrDefault("DATABASE_USER","code_context");
        String password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        if(!admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"))
            throw new IllegalStateException("isolated onboarding fixture requires a test database");
        String schema="onboarding_"+UUID.randomUUID().toString().replace("-","");
        String scoped=url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public";
        var dataSource=new DriverManagerDataSource(scoped,user,password);
        var jdbc=new JdbcTemplate(dataSource);var tx=new JdbcTransactionManager(dataSource);
        var transactions=new TransactionTemplate(tx);
        RepositoryIndexLeaseManager leases=null;IndexingService indexing=null;
        RepositoryOnboarding first=null,second=null;
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .placeholders(Map.of("vectorDimensions","1024")).load().migrate();
            var repositories=new RepositoryStore(jdbc);var props=properties();
            leases=new RepositoryIndexLeaseManager(jdbc);
            indexing=new IndexingService(jdbc,repositories,null,props,null,null,null,tx,null,null,null,null,null,leases);
            var json=JsonMapper.builder().build();var created=new AtomicBoolean();var creates=new AtomicInteger();
            GitHubWebhookClient.Exchange provider=(method,uri,body)->{
                        if(method.equals("GET"))return json.readTree(created.get()?"[{\"id\":91,\"config\":{\"url\":\"https://context.example/webhooks/github/acme/library\"}}]":"[]");
                        if(method.equals("POST")){creates.incrementAndGet();created.set(true);throw new java.io.IOException("simulated lost provider response");}
                        return json.readTree("{\"id\":91,\"active\":true,\"events\":[\"push\"],\"config\":{\"url\":\"https://context.example/webhooks/github/acme/library\"}}");
                    };
            var client=new GitHubWebhookClient(props,json,new GitHubWebhookClient.Settings("fixture-webhook-token","https://api.github.com","api.github.com","https://context.example"),
                    provider,host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});
            first=new RepositoryOnboarding(jdbc,indexing,client,repositories,leases,tx);
            var service=first;
            assertThrows(IllegalStateException.class,()->transactions.executeWithoutResult(status->{
                var rolledBack=repositories.insert("acme","rollback","https://github.com/acme/rollback.git","team","main","SERVICE","/repos/acme/rollback");
                service.schedule(rolledBack);throw new IllegalStateException("controlled registration transaction failure");
            }));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM repositories",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM index_jobs",Integer.class));
            var repository=transactions.execute(status->{
                var registered=repositories.insert("acme","library","https://github.com/acme/library.git","team","main","LIBRARY","/repos/acme/library");
                service.schedule(registered);service.schedule(registered);return registered;
            });
            assertNotNull(repository);
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM index_jobs",Integer.class));
            assertThrows(IllegalStateException.class,()->service.schedule(repository),"registration must not silently enqueue outside its transaction");
            assertTrue(first.reconcileOne());
            assertEquals("QUEUED",jdbc.queryForObject("SELECT webhook_state FROM repository_onboarding",String.class));
            assertEquals(1,creates.get());
            first.stop();
            second=new RepositoryOnboarding(jdbc,indexing,client,repositories,leases,tx);
            // Simulate process death after a durable claim. A later instance must recover the expired claim.
            UUID stale=UUID.randomUUID();
            jdbc.update("UPDATE repository_onboarding SET webhook_state='RUNNING',webhook_claim=?,webhook_claim_until=now()-interval '1 second'",stale);
            assertTrue(second.reconcileOne());
            assertEquals(1,creates.get(),"a lost create response must be recovered by discovery/PATCH, never another POST");
            assertEquals("READY",jdbc.queryForObject("SELECT webhook_state FROM repository_onboarding",String.class));
            assertEquals(91L,jdbc.queryForObject("SELECT webhook_id FROM repository_onboarding",Long.class));
            assertEquals(0,jdbc.update("UPDATE repository_onboarding SET webhook_state='FAILED' WHERE webhook_claim=?",stale),"late worker token must not mutate the recovered state");
            assertFalse(second.reconcileOne(),"periodic reconciliation must respect the durable next-run time");
            var confirmed=jdbc.queryForObject("SELECT webhook_verified_at FROM repository_onboarding",java.sql.Timestamp.class);
            assertEquals(false,second.status(repository).get("webhook_delivery_verified"),"a masked provider secret is not delivery proof");
            // Controlled database fixture for proof aging/rotation; SharedLibrary IT additionally
            // exercises a real signed HTTP delivery, and neither is labelled live GitHub acceptance.
            jdbc.update("INSERT INTO webhook_deliveries(delivery_id,repository_id,payload_sha256,signing_key_fingerprint) VALUES(?,?,?,?)",
                    UUID.randomUUID(),repository.id(),"a".repeat(64),client.signingKeyFingerprint());
            assertEquals(true,second.status(repository).get("webhook_delivery_verified"));
            jdbc.update("UPDATE repository_onboarding SET webhook_available_at=now()-interval '1 second'");
            assertTrue(second.reconcileOne());
            assertEquals(confirmed,jdbc.queryForObject("SELECT webhook_verified_at FROM repository_onboarding",java.sql.Timestamp.class),"unchanged periodic verification must retain established delivery proof");
            var rotatedClient=new GitHubWebhookClient(properties("rotated-fixture-secret"),json,new GitHubWebhookClient.Settings("fixture-webhook-token","https://api.github.com","api.github.com","https://context.example"),
                    provider,host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});
            var rotated=new RepositoryOnboarding(jdbc,indexing,rotatedClient,repositories,leases,tx);
            try {
                assertEquals(false,rotated.status(repository).get("webhook_configuration_current"));
                assertEquals(false,rotated.status(repository).get("webhook_delivery_verified"));
                jdbc.update("UPDATE repository_onboarding SET webhook_available_at=now()-interval '1 second'");
                assertTrue(rotated.reconcileOne());
                assertEquals(true,rotated.status(repository).get("webhook_configuration_current"));
                assertEquals(false,rotated.status(repository).get("webhook_delivery_verified"),"rotation needs a new correctly signed delivery");
            }finally {rotated.stop();rotatedClient.stop();}
            jdbc.update("UPDATE index_jobs SET state='FAILED'");
            second.retry(repository);
            assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM index_jobs",Integer.class));
            second.retry(repository);
            assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM index_jobs",Integer.class),"retrying an already queued initial job must be idempotent");
            client.stop();
        } finally {
            if(first!=null)first.stop();if(second!=null)second.stop();if(indexing!=null)indexing.stopWorkers();if(leases!=null)leases.shutdown();
            admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
    private CodeContextProperties properties(){return properties("fixture-secret");}
    private CodeContextProperties properties(String secret){return new CodeContextProperties("/repos","/fixtures","/zoekt","team=key","team=admin",secret,"","github.com","https://context.example",1024,1048576,6000,2,30,
            new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("graphify",false));}
}
