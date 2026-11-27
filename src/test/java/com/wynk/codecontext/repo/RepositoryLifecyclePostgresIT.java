package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.index.IndexingService;
import com.wynk.codecontext.index.RepositoryIndexLeaseManager;
import org.eclipse.jgit.api.Git;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** PostgreSQL configuration fencing and real owned-mirror transitions; HTTP MCP has separate acceptance. */
class RepositoryLifecyclePostgresIT {
    @TempDir Path temp;

    @Test void preservesIdentityFencesOldConfigurationAndNeverReactivatesAnExplicitlyDisabledRepository()throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        String user=System.getenv().getOrDefault("DATABASE_USER","code_context");
        String password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        if(!admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"))throw new IllegalStateException("lifecycle fixtures require a test database");
        String schema="lifecycle_"+UUID.randomUUID().toString().replace("-","");
        var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);
        var jdbc=new JdbcTemplate(dataSource);var tx=new JdbcTransactionManager(dataSource);var transaction=new TransactionTemplate(tx);
        var properties=properties();var git=new GitRepositoryManager(properties);var json=JsonMapper.builder().build();
        RepositoryIndexLeaseManager leases=null;IndexingService indexing=null;RepositoryOnboarding onboarding=null;
        RepositoryLifecycle lifecycle=null;GitHubWebhookClient hooks=null;
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).placeholders(Map.of("vectorDimensions","1024")).load().migrate();
            var repositories=new RepositoryStore(jdbc);leases=new RepositoryIndexLeaseManager(jdbc);
            indexing=new IndexingService(jdbc,repositories,git,properties,null,null,null,tx,null,null,null,null,null,leases);
            hooks=new GitHubWebhookClient(properties,json,"","https://api.github.com","api.github.com","");
            onboarding=new RepositoryOnboarding(jdbc,indexing,hooks,repositories,leases,tx);
            lifecycle=new RepositoryLifecycle(jdbc,properties,git,indexing,onboarding,leases,tx,json,hooks);
            Path original=source("original","alpha"),relocated=source("relocated","bravo");
            try(var repository=Git.open(relocated.toFile())){repository.branchCreate().setName("pilot").call();}
            String oldMirror=temp.resolve("mirrors/acme/original").toString();
            var first=repositories.insert("acme","original",original.toUri().toString(),"team","main","SERVICE",oldMirror);
            var identityService=lifecycle;
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->identityService.requireIdentityAvailable("ACME","ORIGINAL",null));
            assertDoesNotThrow(()->identityService.requireIdentityAvailable("ACME","ORIGINAL",first.id()));
            jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES('team',?)",first.id());
            git.sync(first);String before=git.head(Path.of(oldMirror));
            UUID generation=jdbc.queryForObject("INSERT INTO index_generations(repository_id,commit_sha,generation,published) VALUES(?,?,1,true) RETURNING id",UUID.class,first.id(),before);
            var service=onboarding;transaction.executeWithoutResult(status->service.schedule(first));
            jdbc.update("UPDATE repository_onboarding SET provider_repository_id=81,webhook_id=91,webhook_callback_url='https://context.example/webhooks/github/acme/original',webhook_claim=? WHERE repository_id=?",UUID.randomUUID(),first.id());
            var held=leases.tryAcquire(first.id(),UUID.randomUUID()).orElseThrow();
            try {
                lifecycle.update(first.id(),"team",new RepositoryLifecycle.Update("acme","renamed",relocated.toUri().toString(),"pilot","LIBRARY","next",1L));
                assertEquals(false,jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,first.id()));
                assertNull(jdbc.queryForObject("SELECT provider_repository_id FROM repository_onboarding WHERE repository_id=?",Long.class,first.id()),"an explicit URL relocation must invalidate the current provider binding");
                assertNull(jdbc.queryForObject("SELECT webhook_claim FROM repository_onboarding WHERE repository_id=?",UUID.class,first.id()),"the previous provider worker must lose its claim before a new configuration becomes visible");
                assertEquals(81L,jdbc.queryForObject("SELECT previous_provider_repository_id FROM repository_lifecycle_work WHERE repository_id=?",Long.class,first.id()),"retirement must retain the old immutable provider identity");
                assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM team_repository_access WHERE repository_id=?",Integer.class,first.id()));
                held.assertOwned();
                var rejected=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE index_generations SET published=true WHERE id=?",generation));
                Throwable root=rejected;while(root.getCause()!=null)root=root.getCause();
                assertInstanceOf(java.sql.SQLException.class,root);assertEquals("23514",((java.sql.SQLException)root).getSQLState());
                assertTrue(lifecycle.processOne());
                assertTrue(Files.exists(Path.of(oldMirror)),"a valid old repository lease must defer physical cleanup");
                assertEquals("QUEUED",jdbc.queryForObject("SELECT state FROM repository_lifecycle_work",String.class));
                assertThrows(org.springframework.web.server.ResponseStatusException.class,()->identityService.requireMirrorAvailable(Path.of(oldMirror.toUpperCase(java.util.Locale.ROOT)),null),"case aliases must not reclaim an old mirror while cleanup is pending");
            }finally{held.close();}
            jdbc.update("UPDATE repository_lifecycle_work SET available_at=now()-interval '1 second'");
            assertTrue(lifecycle.processOne());
            var current=repositories.find("acme","renamed").orElseThrow();
            assertEquals(first.id(),current.id());assertEquals("next",current.team());assertEquals("pilot",current.defaultBranch());
            assertTrue(repositories.find("acme","original").isEmpty());assertFalse(Files.exists(Path.of(oldMirror)));
            assertTrue(Files.isRegularFile(original.resolve("marker.txt")),"cleanup must not delete the remote source repository");
            assertEquals("next",jdbc.queryForObject("SELECT team FROM team_repository_access WHERE repository_id=?",String.class,first.id()));
            assertEquals("acme",jdbc.queryForObject("SELECT owner FROM generation_cleanup_outbox WHERE generation_id=?",String.class,generation));
            assertEquals("original",jdbc.queryForObject("SELECT name FROM generation_cleanup_outbox WHERE generation_id=?",String.class,generation));
            assertEquals(false,jdbc.queryForObject("SELECT published FROM index_generations WHERE id=?",Boolean.class,generation));
            assertEquals(2L,lifecycle.status(first.id(),"next").get("revision"));
            var ownerService=lifecycle;assertThrows(org.springframework.web.server.ResponseStatusException.class,()->ownerService.status(first.id(),"team"));
            lifecycle.update(first.id(),"next",new RepositoryLifecycle.Update(null,null,null,null,null,null,2L));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM repository_lifecycle_work",Integer.class),"an identical retry must not create another transition");
            git.sync(current);
            lifecycle.update(first.id(),"next",new RepositoryLifecycle.Update(null,null,null,"main",null,null,2L));
            repositories.disable(first.id());
            assertTrue(lifecycle.processOne());
            assertEquals(false,jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,first.id()));
            assertEquals(false,jdbc.queryForObject("SELECT desired_enabled FROM repositories WHERE id=?",Boolean.class,first.id()));
            var previous=repositories.findAny("acme","renamed").orElseThrow();
            transaction.executeWithoutResult(status->{
                var restored=repositories.insert("acme","renamed",relocated.toUri().toString(),"next","main","LIBRARY",previous.mirrorPath());
                ownerService.reactivate(previous,restored);
            });
            assertTrue(lifecycle.processOne());assertEquals(first.id(),repositories.id("acme","renamed"));
            assertEquals(4L,lifecycle.status(first.id(),"next").get("revision"));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM index_jobs WHERE repository_id=? AND state='QUEUED'",Integer.class,first.id()));
        } finally {
            if(lifecycle!=null)lifecycle.stop();if(onboarding!=null)onboarding.stop();if(hooks!=null)hooks.stop();
            if(indexing!=null)indexing.stopWorkers();if(leases!=null)leases.shutdown();admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
    private Path source(String name,String text)throws Exception {
        Path path=Files.createDirectories(temp.resolve("sources").resolve(name));
        try(var git=Git.init().setDirectory(path.toFile()).setInitialBranch("main").call()) {
            Files.writeString(path.resolve("marker.txt"),text);git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("fixture","fixture@example.test").setCommitter("fixture","fixture@example.test").call();
        }return path;
    }
    private CodeContextProperties properties(){return new CodeContextProperties(temp.resolve("mirrors").toString(),temp.resolve("sources").toString(),temp.resolve("zoekt").toString(),
            "team=key,next=next-key","team=admin,next=next-admin","fixture-secret","","github.com","https://context.example",1024,1048576,6000,1,30,
            new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("graphify",false));}
}
