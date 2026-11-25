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
        var service=new IndexingService(jdbc,mock(RepositoryStore.class),mock(GitRepositoryManager.class),props(),
                mock(EmbeddingModel.class),mock(ZoektIndexer.class),mock(GraphifyIndexer.class),
                mock(PlatformTransactionManager.class),mock(ContractIndexBuilder.class),
                JsonMapper.builder().build(),mock(GraphSymbolBinder.class),mock(ConsumerContractIndexRefresher.class),
                mock(PostgresContractIndexStore.class),mock(RepositoryIndexLeaseManager.class));
        UUID job=UUID.randomUUID();
        service.retryAttempt(job,3,new IllegalStateException("permanent fixture failure"));
        verify(jdbc).update(startsWith("UPDATE index_jobs SET state=CASE WHEN attempt>=3 THEN 'FAILED'"),
                contains("permanent fixture failure"),anyInt(),eq(job),eq(3));
        assertTrue(true);
    }

    private static CodeContextProperties props() {
        return new CodeContextProperties("/tmp/repos","/tmp/local","/tmp/zoekt","t=t","a=a","","","github.com","http://localhost",
                1024,1024,6000,2,300,new CodeContextProperties.Zoekt("zoekt-git-index","zoekt-local-sync","http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify",false));
    }
}
