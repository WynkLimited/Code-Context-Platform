package com.wynk.codecontext.index;

import com.wynk.codecontext.CodeContextApplication;
import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.search.ZoektIndexer;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Test-only process entry point: a full production application, with a publication-boundary latch. */
public final class ReplicaApplicationProcess {
    private static Path directory;
    public static void main(String[] args) throws Exception {
        directory=Path.of(args[0]);
        var application=new SpringApplication(CodeContextApplication.class,PublicationLatch.class,CrashLeaseTimings.class);
        var context=application.run();
        int port=((WebServerApplicationContext)context).getWebServer().getPort();
        LeaseWorkerProcess.signal(directory.resolve("ready"),Integer.toString(port));
    }
    @TestConfiguration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("replica-crash-fixture")
    static class CrashLeaseTimings {
        @Bean @Primary RepositoryIndexLeaseManager crashFixtureLeases(org.springframework.jdbc.core.JdbcTemplate jdbc) {
            return new RepositoryIndexLeaseManager(jdbc,java.time.Duration.ofSeconds(3),java.time.Duration.ofMillis(200));
        }
    }
    @TestConfiguration(proxyBeanMethods=false)
    @org.springframework.context.annotation.Profile("replica-consistency-fixture")
    static class PublicationLatch {
        @Bean @Primary ZoektIndexer replicaZoektIndexer(CodeContextProperties properties,JsonMapper json) {
            return new ZoektIndexer(properties,json) {
                @Override public void index(RepositoryRecord repository,Path checkout,UUID generation) throws Exception {
                    super.index(repository,checkout,generation);
                    if(Files.exists(directory.resolve("pause"))) {
                        LeaseWorkerProcess.signal(directory.resolve("paused"),generation.toString());
                        while(!Files.exists(directory.resolve("resume")))Thread.sleep(50);
                    }
                }
            };
        }
    }
}
