package com.wynk.codecontext.index;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.time.Duration;
import java.util.UUID;

/** Separate JVM controlled by RepositoryLeaseProcessPostgresIT; never starts an application server. */
public final class LeaseWorkerProcess {
    public static void main(String[] args)throws Exception {
        UUID repository=UUID.fromString(args[0]), job=UUID.fromString(args[1]), candidate=UUID.fromString(args[2]);
        Path directory=Path.of(args[3]);
        var ds=new DriverManagerDataSource(System.getenv("LEASE_TEST_URL"),System.getenv("LEASE_TEST_USER"),System.getenv("LEASE_TEST_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        var manager=new RepositoryIndexLeaseManager(jdbc,Duration.ofSeconds(3),Duration.ofMillis(200));
        try {
            var claim=manager.tryAcquire(repository,job);
            if(claim.isEmpty()){signal(directory.resolve("status"),"BUSY");return;}
            try(var lease=claim.get()) {
                signal(directory.resolve("status"),"READY");
                long deadline=System.nanoTime()+Duration.ofMinutes(1).toNanos();
                while(System.nanoTime()<deadline) {
                    Path command=directory.resolve("command");
                    if(!Files.exists(command)){Thread.sleep(25);continue;}
                    String action=Files.readString(command);Files.delete(command);
                    if(action.equals("STOP_HEARTBEAT")) {
                        manager.shutdown();
                        signal(directory.resolve("status"),"HEARTBEAT_STOPPED");
                    } else if(action.equals("PUBLISH")) {
                        try {
                            new TransactionTemplate(new DataSourceTransactionManager(ds)).execute(status->{
                                lease.fenceTransaction();
                                jdbc.update("UPDATE index_generations SET published=false WHERE repository_id=?",repository);
                                if(jdbc.update("UPDATE index_generations SET published=true WHERE id=? AND repository_id=?",candidate,repository)!=1)
                                    throw new IllegalStateException("missing fixture generation");
                                return null;
                            });
                            signal(directory.resolve("status"),"PUBLISHED");
                        }catch(IllegalStateException rejected) {
                            if(!rejected.getMessage().contains("lease is no longer valid"))throw rejected;
                            signal(directory.resolve("status"),"REJECTED_STALE_LEASE");
                        }
                    }else if(action.equals("EXIT"))return;
                    else throw new IllegalArgumentException("unknown fixture command");
                }
                throw new IllegalStateException("fixture controller did not terminate worker within one minute");
            }
        }finally {manager.shutdown();}
    }
    static void signal(Path file,String value)throws Exception {
        Path temporary=file.resolveSibling(file.getFileName()+".new");
        Files.writeString(temporary,value);
        Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
}
