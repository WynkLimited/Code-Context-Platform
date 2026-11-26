package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real DB, signed App JWTs and controlled provider permissions; not live GitHub certification. */
class GitHubAppAccessPostgresIT {
    @TempDir Path temp;
    private final JsonMapper json=JsonMapper.builder().build();
    @Test void revokesOnlyBoundAccessAtomicallyAndInvalidatesCredentialsAcrossReplicas()throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        String user=System.getenv().getOrDefault("DATABASE_USER","code_context"),password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        if(!admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"))throw new IllegalStateException("App access fixture requires a test database");
        String schema="app_access_"+UUID.randomUUID().toString().replace("-","");
        var ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);
        var jdbc=new JdbcTemplate(ds);var tx=new JdbcTransactionManager(ds);var transactions=new TransactionTemplate(tx);
        GitHubAppIntegration first=null,second=null,third=null;
        try {
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema).placeholders(Map.of("vectorDimensions","1024")).load().migrate();
            var repositories=new RepositoryStore(jdbc);var a=new GitHubAppAccess(jdbc,repositories,tx,"https://api.github.com");
            var b=new GitHubAppAccess(jdbc,repositories,tx,"https://api.github.com");
            UUID repository=bind(jdbc,repositories,"library",81,17),sameInstallation=bind(jdbc,repositories,"retained",83,17),foreign=bind(jdbc,repositories,"foreign",82,18);
            var legacyBinding=repositories.insert("acme","legacy","HTTPS://GitHub.com/acme/legacy.git","team","main","SERVICE","/repos/acme/legacy");
            jdbc.update("INSERT INTO repository_onboarding(repository_id,webhook_mode,webhook_state,provider_repository_id) VALUES(?,'APP','READY',84)",legacyBinding.id());
            UUID generation=jdbc.queryForObject("INSERT INTO index_generations(repository_id,commit_sha,generation,published) VALUES(?,'"+"a".repeat(40)+"',1,true) RETURNING id",UUID.class,repository);
            jdbc.update("INSERT INTO index_jobs(repository_id,commit_sha,requested_commit_sha,state,idempotency_key) VALUES(?,'HEAD','HEAD','QUEUED','app-access-fixture')",repository);
            JsonNode removed=payload("removed",81);UUID delivery=UUID.randomUUID();
            jdbc.execute("CREATE FUNCTION reject_revocation_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled revocation persistence failure'; END $$");
            jdbc.execute("CREATE TRIGGER reject_revocation_fixture BEFORE UPDATE OF enabled ON repositories FOR EACH ROW EXECUTE FUNCTION reject_revocation_fixture()");
            assertThrows(org.springframework.dao.DataAccessException.class,()->a.receive(delivery,"installation_repositories",hash(removed),removed));
            assertEquals(0L,a.epoch("github.com",17));assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM github_event_receipts",Integer.class));
            assertTrue(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,repository));
            assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM index_jobs",String.class));
            jdbc.execute("DROP TRIGGER reject_revocation_fixture ON repositories");

            var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
            Path key=temp.resolve("app.pem");Files.writeString(key,"-----BEGIN PRIVATE KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{'\n'}).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
            var allowed=new AtomicBoolean(true);var issued=new AtomicInteger();var invalidateDuringIssue=new AtomicBoolean();Instant now=Instant.now();
            GitHubAppIntegration.Exchange provider=(method,uri,credential,body)->{
                if(uri.getPath().endsWith("/installation")) {
                    if(!allowed.get())throw new java.io.IOException("controlled provider authorization revoked");
                    return json.readTree("{\"id\":17,\"permissions\":{\"contents\":\"read\",\"metadata\":\"read\"},\"events\":[\"push\"],\"suspended_at\":null}");
                }
                if(method.equals("POST")) {
                    int number=issued.incrementAndGet();
                    if(invalidateDuringIssue.compareAndSet(true,false)){var event=payload("suspend",0);b.receive(UUID.randomUUID(),"installation",hash(event),event);}
                    return json.readTree(json.writeValueAsString(Map.of("token","ghs_controlled_access_fixture_"+number,"expires_at",now.plusSeconds(3600).toString(),"permissions",Map.of("contents","read","metadata","read"))));
                }
                return json.readTree("{\"total_count\":1,\"repositories\":[{\"id\":81,\"full_name\":\"acme/library\"}]}");
            };
            var props=properties();var settings=new GitHubAppIntegration.Settings("fixture-app",key.toString(),"https://api.github.com","api.github.com","https://context.example");
            first=new GitHubAppIntegration(props,json,settings,Clock.fixed(now,ZoneOffset.UTC),provider,host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});first.access(a);
            second=new GitHubAppIntegration(props,json,settings,Clock.fixed(now,ZoneOffset.UTC),provider,host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});second.access(b);
            String clone="https://github.com/acme/library.git",one=first.contentToken(clone),two=second.contentToken(clone);
            assertEquals(one,first.contentToken(clone));assertEquals(two,second.contentToken(clone));assertEquals(2,issued.get());
            assertEquals(1,a.receive(delivery,"installation_repositories",hash(removed),removed).get("repositoriesSuspended"));
            assertEquals(true,b.receive(delivery,"installation_repositories",hash(removed),removed).get("duplicate"));assertEquals(1L,b.epoch("github.com",17));
            assertFalse(jdbc.queryForObject("SELECT enabled OR desired_enabled FROM repositories WHERE id=?",Boolean.class,repository));
            assertTrue(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,sameInstallation));assertTrue(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,foreign));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM team_repository_access WHERE repository_id=?",Integer.class,repository));
            assertEquals("FAILED",jdbc.queryForObject("SELECT state FROM index_jobs",String.class));
            var stale=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE index_generations SET published=true WHERE id=?",generation));
            Throwable root=stale;while(root.getCause()!=null)root=root.getCause();assertEquals("23514",((java.sql.SQLException)root).getSQLState());
            allowed.set(false);var replicaOne=first;var replicaTwo=second;
            assertThrows(IllegalStateException.class,()->replicaOne.contentToken(clone));assertThrows(IllegalStateException.class,()->replicaTwo.contentToken(clone));
            assertEquals(2,issued.get(),"both replicas must recheck the provider rather than return their still-unexpired cached tokens");
            var changed=payload("removed",83);
            assertEquals(409,assertThrows(ResponseStatusException.class,()->a.receive(delivery,"installation_repositories",hash(changed),changed)).getStatusCode().value());
            UUID pushId=UUID.randomUUID();transactions.executeWithoutResult(status->a.claim(pushId,"push","a".repeat(64)));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->a.receive(pushId,"installation_repositories",hash(removed),removed)).getStatusCode().value());
            byte[] oldPush=json.writeValueAsBytes(Map.of("after","b".repeat(40),"repository",Map.of("full_name","acme/foreign")));
            String oldHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(oldPush));
            UUID retainedPush=UUID.randomUUID();transactions.executeWithoutResult(status->a.claim(retainedPush,"push",oldHash));
            var mac=javax.crypto.Mac.getInstance("HmacSHA256");mac.init(new javax.crypto.spec.SecretKeySpec(props.webhookSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
            var controller=new com.wynk.codecontext.index.IndexWebhookController(null,props,json,repositories,jdbc,tx,a);
            var duplicate=controller.github("acme","foreign","sha256="+java.util.HexFormat.of().formatHex(mac.doFinal(oldPush)),retainedPush.toString(),"push",oldPush);
            assertEquals(true,duplicate.get("duplicate"),"a retained common receipt must prevent replay after repository-scoped delivery history is removed");
            UUID legacy=UUID.randomUUID();jdbc.update("INSERT INTO webhook_deliveries(delivery_id,repository_id,payload_sha256) VALUES(?,?,?)",legacy,foreign,"b".repeat(64));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->a.receive(legacy,"installation_repositories",hash(removed),removed)).getStatusCode().value());
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM github_event_receipts WHERE delivery_id=?",Integer.class,legacy));
            allowed.set(true);assertNotEquals(one,first.contentToken(clone));assertTrue(repositories.find("acme","library").isEmpty(),"fresh provider authorization alone must not restore platform access");
            third=new GitHubAppIntegration(props,json,settings,Clock.fixed(now,ZoneOffset.UTC),provider,host->new InetAddress[]{InetAddress.getByName("203.0.113.10")});third.access(b);var newReplica=third;
            invalidateDuringIssue.set(true);assertThrows(IllegalStateException.class,()->newReplica.contentToken(clone),"a revocation committed during token issuance must prevent caching that credential");
            assertFalse(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,sameInstallation));assertTrue(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,foreign));
            assertFalse(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,legacyBinding.id()),"legacy bindings without installation provenance must be conservatively suspended, including HTTPS scheme case aliases");
            var resumed=payload("unsuspend",0);a.receive(UUID.randomUUID(),"installation",hash(resumed),resumed);
            assertTrue(repositories.find("acme","library").isEmpty());assertTrue(repositories.find("acme","retained").isEmpty());
        }finally {
            if(first!=null)first.stop();if(second!=null)second.stop();if(third!=null)third.stop();admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
    private UUID bind(JdbcTemplate jdbc,RepositoryStore repositories,String name,long repository,long installation) {
        var registered=repositories.insert("acme",name,"https://github.com/acme/"+name+".git","team","main","SERVICE","/repos/acme/"+name);
        jdbc.update("INSERT INTO repository_onboarding(repository_id,webhook_mode,webhook_state,provider_repository_id,provider_installation_id,provider_host) VALUES(?,'APP','READY',?,?,'github.com')",registered.id(),repository,installation);
        jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES('team',?)",registered.id());return registered.id();
    }
    private JsonNode payload(String action,long repository) {
        return json.valueToTree(repository>0?Map.of("action",action,"installation",Map.of("id",17),"repositories_removed",java.util.List.of(Map.of("id",repository))):Map.of("action",action,"installation",Map.of("id",17)));
    }
    private String hash(JsonNode payload)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(payload)));}
    private CodeContextProperties properties(){return new CodeContextProperties(temp.resolve("repos").toString(),temp.resolve("sources").toString(),temp.resolve("zoekt").toString(),"team=key","team=admin","fixture-secret","","github.com","https://context.example",1024,1048576,6000,1,30,
            new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("graphify",false));}
}
