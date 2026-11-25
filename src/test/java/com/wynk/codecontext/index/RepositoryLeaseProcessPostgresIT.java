package com.wynk.codecontext.index;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL and independent worker JVMs: renewal, stale fencing, crash and failover. */
class RepositoryLeaseProcessPostgresIT {
    @TempDir Path root;
    private final List<Process> workers=new ArrayList<>();
    @Test void staleWorkerCannotPublishAndAnotherJvmRecoversAfterCrash()throws Exception {
        String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        String user=System.getenv().getOrDefault("DATABASE_USER","code_context");
        String password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        assertTrue(admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"));
        admin.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
        admin.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
        String schema="process_lease_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE SCHEMA "+schema);
        String isolatedUrl=url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public";
        var ds=new DriverManagerDataSource(isolatedUrl,user,password);
        var jdbc=new JdbcTemplate(ds);
        try {
            Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                    .placeholders(Map.of("vectorDimensions","1024")).load().migrate();
            UUID repository=jdbc.queryForObject("INSERT INTO repositories(owner,name,clone_url,team,mirror_path) VALUES('fixture','process-leases','','team','') RETURNING id",UUID.class);
            UUID initial=generation(jdbc,repository,0,true),stale=generation(jdbc,repository,1,false),fresh=generation(jdbc,repository,2,false),recovered=generation(jdbc,repository,3,false);
            UUID job=jdbc.queryForObject("INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state,updated_at) VALUES(?,'fixture','process-fixture','RUNNING',now()-interval '1 minute') RETURNING id",UUID.class,repository);
            Path first=start("first",repository,job,stale,isolatedUrl,user,password);
            awaitStatus(first,"READY");
            Timestamp heartbeat=jdbc.queryForObject("SELECT updated_at FROM index_jobs WHERE id=?",Timestamp.class,job);
            await(()->jdbc.queryForObject("SELECT updated_at>? FROM index_jobs WHERE id=?",Boolean.class,heartbeat,job),"worker heartbeat did not refresh the durable job");
            Path contender=start("contender",repository,job,fresh,isolatedUrl,user,password);
            awaitStatus(contender,"BUSY");
            LeaseWorkerProcess.signal(first.resolve("command"),"STOP_HEARTBEAT");
            awaitStatus(first,"HEARTBEAT_STOPPED");
            awaitExpiry(jdbc,repository);
            Path second=start("second",repository,job,fresh,isolatedUrl,user,password);
            awaitStatus(second,"READY");
            LeaseWorkerProcess.signal(second.resolve("command"),"PUBLISH");
            awaitStatus(second,"PUBLISHED");
            LeaseWorkerProcess.signal(first.resolve("command"),"PUBLISH");
            awaitStatus(first,"REJECTED_STALE_LEASE");
            assertEquals(fresh,published(jdbc,repository),"stale JVM must not roll back the new owner's publication");
            assertFalse(jdbc.queryForObject("SELECT published FROM index_generations WHERE id=?",Boolean.class,initial));
            Process crashed=workers.get(2);
            crashed.destroyForcibly();assertTrue(crashed.waitFor(5,TimeUnit.SECONDS));
            awaitExpiry(jdbc,repository);
            Path restarted=start("restarted",repository,job,recovered,isolatedUrl,user,password);
            awaitStatus(restarted,"READY");
            LeaseWorkerProcess.signal(restarted.resolve("command"),"PUBLISH");
            awaitStatus(restarted,"PUBLISHED");
            assertEquals(recovered,published(jdbc,repository),"new JVM must recover ownership after forced worker termination");
        }finally {
            for(Process worker:workers)if(worker.isAlive())worker.destroyForcibly();
            for(Process worker:workers)assertTrue(worker.waitFor(5,TimeUnit.SECONDS),"fixture worker did not terminate");
            Path logs=Files.createDirectories(Path.of("target","lease-process-logs",schema));
            try(var directories=Files.list(root)) {
                for(Path directory:directories.toList())if(Files.exists(directory.resolve("worker.log")))
                    Files.copy(directory.resolve("worker.log"),logs.resolve(directory.getFileName()+".log"),StandardCopyOption.REPLACE_EXISTING);
            }
            admin.execute("DROP SCHEMA "+schema+" CASCADE");
        }
    }
    private Path start(String name,UUID repository,UUID job,UUID generation,String url,String user,String password)throws Exception {
        Path directory=Files.createDirectory(root.resolve(name));
        String classpath=System.getProperty("surefire.test.class.path",System.getProperty("java.class.path"));
        var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-cp",classpath,
                LeaseWorkerProcess.class.getName(),repository.toString(),job.toString(),generation.toString(),directory.toString());
        command.environment().put("LEASE_TEST_URL",url);
        command.environment().put("LEASE_TEST_USER",user);
        command.environment().put("LEASE_TEST_PASSWORD",password);
        workers.add(command.redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start());
        return directory;
    }
    private void awaitStatus(Path directory,String expected)throws Exception {
        await(()->{
            try{return Files.exists(directory.resolve("status"))&&Files.readString(directory.resolve("status")).equals(expected);}
            catch(java.io.IOException failure){return false;}
        },"worker did not reach "+expected+"; inspect "+directory.resolve("worker.log"));
    }
    private void awaitExpiry(JdbcTemplate jdbc,UUID repository)throws Exception {
        await(()->jdbc.queryForObject("SELECT lease_expires_at<clock_timestamp() FROM repository_index_leases WHERE repository_id=?",Boolean.class,repository),"lease did not expire after heartbeat stopped");
    }
    private static void await(BooleanSupplier condition,String message)throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(15).toNanos();
        while(System.nanoTime()<deadline){if(condition.getAsBoolean())return;Thread.sleep(50);}
        fail(message);
    }
    private UUID generation(JdbcTemplate jdbc,UUID repo,int number,boolean published) {
        return jdbc.queryForObject("INSERT INTO index_generations(repository_id,commit_sha,generation,published) VALUES(?,?,?,?) RETURNING id",UUID.class,repo,"commit-"+number,number,published);
    }
    private UUID published(JdbcTemplate jdbc,UUID repo){return jdbc.queryForObject("SELECT id FROM index_generations WHERE repository_id=? AND published",UUID.class,repo);}
}
