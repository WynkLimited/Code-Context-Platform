package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.index.IndexingService;
import com.wynk.codecontext.index.RepositoryIndexLeaseManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL scheduling atomicity; actual worker-to-MCP recovery is in SharedLibraryImpactPostgresIT. */
class RepositoryRefreshPostgresIT {
    @Test void queuesMissedEventRecoveryAtomicallyAndCoalescesCompetingSchedulers()throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        String user=System.getenv().getOrDefault("DATABASE_USER","code_context"),password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        if(!admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"))throw new IllegalStateException("refresh fixture requires a test database");
        String schema="refresh_"+UUID.randomUUID().toString().replace("-","");
        var dataSource=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public",user,password);
        var jdbc=new JdbcTemplate(dataSource);var tx=new JdbcTransactionManager(dataSource);
        RepositoryIndexLeaseManager leases=null;IndexingService indexing=null;
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).placeholders(Map.of("vectorDimensions","1024")).load().migrate();
            var repositories=new RepositoryStore(jdbc);leases=new RepositoryIndexLeaseManager(jdbc);
            var props=new CodeContextProperties("/repos","/fixtures","/zoekt","team=key","team=admin","fixture-secret","","github.com","https://context.example",1024,1048576,6000,1,30,
                    new CodeContextProperties.Zoekt("index","clean","http://zoekt"),new CodeContextProperties.Graphify("graphify",false));
            indexing=new IndexingService(jdbc,repositories,null,props,null,null,null,tx,null,null,null,null,null,leases);
            var first=new RepositoryRefresh(jdbc,indexing,tx,300);var second=new RepositoryRefresh(jdbc,indexing,tx,300);
            var repository=repositories.insert("fixture","missed-event","https://github.com/fixture/missed-event.git","team","main","SERVICE","/repos/fixture/missed-event");
            jdbc.update("UPDATE repositories SET refresh_due_at=now()-interval '1 second' WHERE id=?",repository.id());
            var due=jdbc.queryForObject("SELECT refresh_due_at FROM repositories",java.sql.Timestamp.class);
            // Fail after enqueue but before schedule persistence: both effects must roll back.
            jdbc.execute("CREATE FUNCTION reject_refresh_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled schedule persistence failure'; END $$");
            jdbc.execute("CREATE TRIGGER reject_refresh_fixture BEFORE UPDATE OF last_refresh_job_id ON repositories FOR EACH ROW EXECUTE FUNCTION reject_refresh_fixture()");
            assertThrows(org.springframework.dao.DataAccessException.class,first::enqueueOneDue);
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM index_jobs",Integer.class));
            assertEquals(due,jdbc.queryForObject("SELECT refresh_due_at FROM repositories",java.sql.Timestamp.class));
            jdbc.execute("DROP TRIGGER reject_refresh_fixture ON repositories");
            var start=new CountDownLatch(1);
            try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var a=workers.submit(()->{start.await();return first.enqueueOneDue();});
                var b=workers.submit(()->{start.await();return second.enqueueOneDue();});start.countDown();
                assertNotEquals(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS),"competing replicas must create only one due refresh");
            }
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM index_jobs",Integer.class));
            var job=jdbc.queryForMap("SELECT * FROM index_jobs");
            assertEquals(repository.id(),job.get("repository_id"));assertEquals("HEAD",job.get("requested_commit_sha"));assertEquals("QUEUED",job.get("state"));
            assertEquals(job.get("id"),jdbc.queryForObject("SELECT last_refresh_job_id FROM repositories",UUID.class));
            assertTrue(jdbc.queryForObject("SELECT refresh_due_at>now()+interval '290 seconds' FROM repositories",Boolean.class));
            jdbc.update("UPDATE repositories SET refresh_due_at=now()-interval '1 second'");
            assertFalse(first.enqueueOneDue(),"an active queued job coalesces missed-event checks");
            jdbc.update("UPDATE index_jobs SET state='RUNNING'");assertFalse(first.enqueueOneDue());
            jdbc.update("UPDATE index_jobs SET state='FAILED'");
            jdbc.update("INSERT INTO repository_lifecycle_work(repository_id,target_revision,repository_root,previous_repository,reset_mirror) VALUES(?,2,'/repos','{}'::jsonb,false)",repository.id());
            assertFalse(first.enqueueOneDue(),"pending lifecycle preparation must defer refresh");
            jdbc.update("UPDATE repository_lifecycle_work SET state='DONE'");
            repositories.disable(repository.id());assertFalse(first.enqueueOneDue(),"disabled repositories must never be refreshed");
            repositories.insert("fixture","missed-event",repository.cloneUrl(),"team","main","SERVICE",repository.mirrorPath());
            assertTrue(first.enqueueOneDue(),"a later eligible refresh must remain possible after failure and explicit reactivation");
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM index_jobs",Integer.class));
        }finally {
            if(indexing!=null)indexing.stopWorkers();if(leases!=null)leases.shutdown();admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }
}
