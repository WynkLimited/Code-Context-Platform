package com.wynk.codecontext.index;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.port.contract.ContractIndexBuilder;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.port.contract.graph.GraphSymbolBinder;
import com.wynk.codecontext.repo.GitRepositoryManager;
import com.wynk.codecontext.repo.RepositoryStore;
import com.wynk.codecontext.search.ZoektIndexer;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import com.wynk.codecontext.ops.PlatformMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IndexingPermanentFailureTest {
    @Test void retryAttemptMarksJobFailedOnceAttemptCeilingIsReached() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE index_jobs SET state=CASE WHEN attempt>=3 THEN 'FAILED'"),any(),anyInt(),any(),anyInt()))
                .thenReturn(1);
        var metrics=new PlatformMetrics(new SimpleMeterRegistry());
        var service=service(jdbc,metrics);
        UUID job=UUID.randomUUID();
        try {
            service.retryAttempt(job,3,new IllegalStateException("permanent fixture failure"));
            verify(jdbc).update(startsWith("UPDATE index_jobs SET state=CASE WHEN attempt>=3 THEN 'FAILED'"),
                    contains("permanent fixture failure"),eq(20),eq(job),eq(3));
            assertEquals(1,metrics.indexFailedCount());
        } finally {service.stopWorkers();}
    }
    @Test void staleOrAlreadyFinishedAttemptsCannotCountAsNewFailures() {
        var jdbc=mock(JdbcTemplate.class);
        var metrics=new PlatformMetrics(new SimpleMeterRegistry());
        var service=service(jdbc,metrics);
        try {
            UUID job=UUID.randomUUID();
            service.retryAttempt(job,3,new IllegalStateException("stale callback"));
            service.retryAttempt(job,3,new IllegalStateException("duplicate callback"));
            assertEquals(0,metrics.indexFailedCount(),"zero-row guarded updates are not durable failures");
        } finally {service.stopWorkers();}
    }
    @Test void retryableAttemptsDoNotCountAsPermanentFailures() {
        var jdbc=mock(JdbcTemplate.class);
        when(jdbc.update(startsWith("UPDATE index_jobs SET state=CASE"),any(),anyInt(),any(),anyInt())).thenReturn(1);
        var metrics=new PlatformMetrics(new SimpleMeterRegistry());
        var service=service(jdbc,metrics);
        try {
            UUID job=UUID.randomUUID();
            service.retryAttempt(job,1,new IllegalStateException("first retry"));
            service.retryAttempt(job,2,new IllegalStateException("second retry"));
            assertEquals(0,metrics.indexFailedCount());
            verify(jdbc).update(startsWith("UPDATE index_jobs SET state=CASE"),eq("first retry"),eq(5),eq(job),eq(1));
            verify(jdbc).update(startsWith("UPDATE index_jobs SET state=CASE"),eq("second retry"),eq(10),eq(job),eq(2));
        } finally {service.stopWorkers();}
    }
    private static IndexingService service(JdbcTemplate jdbc,PlatformMetrics metrics) {
        return new IndexingService(jdbc,mock(RepositoryStore.class),mock(GitRepositoryManager.class),props(),
                mock(EmbeddingModel.class),mock(ZoektIndexer.class),mock(GraphifyIndexer.class),
                mock(PlatformTransactionManager.class),mock(ContractIndexBuilder.class),
                JsonMapper.builder().build(),mock(GraphSymbolBinder.class),mock(ConsumerContractIndexRefresher.class),
                mock(PostgresContractIndexStore.class),mock(RepositoryIndexLeaseManager.class),metrics);
    }

    private static CodeContextProperties props() {
        return new CodeContextProperties("/tmp/repos","/tmp/local","/tmp/zoekt","t=t","a=a","","","github.com","http://localhost",
                1024,1024,6000,2,300,new CodeContextProperties.Zoekt("zoekt-git-index","zoekt-local-sync","http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify",false));
    }
}
