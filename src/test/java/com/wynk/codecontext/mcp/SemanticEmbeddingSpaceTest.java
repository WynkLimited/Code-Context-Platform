package com.wynk.codecontext.mcp;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.index.EmbeddingSpace;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SemanticEmbeddingSpaceTest {
    @Test void legacySpaceRequiresReindexingWithoutEmbeddingOrSearching() {
        withTeam(()->{
            var fixture=new Fixture(1);
            var error=assertThrows(IllegalStateException.class,()->fixture.tools.semantic_search("query",null,10));
            assertTrue(error.getMessage().contains("reindexing"));
            verify(fixture.model,never()).embed(anyString());
            verify(fixture.jdbc,never()).queryForList(anyString(),any(Object[].class));
        });
    }
    @Test void freshSearchBindsTheSameFingerprintAndChecksProviderAfterQuery() {
        withTeam(()->{
            var fixture=new Fixture(0);
            assertEquals("[{\"file_path\":\"Money.java\"}]",fixture.tools.semantic_search("query",null,10));
            verify(fixture.jdbc).queryForList(contains("g.embedding_fingerprint=?"),eq("[1.0,0.0,0.0]"),eq("team"),eq(fixture.space.fingerprint()),eq("[1.0,0.0,0.0]"));
            verify((EmbeddingSpace.IdentifiedModel)fixture.model,times(2)).embeddingSpace(3);
        });
    }
    @Test void changingTheProviderDuringSearchCannotReturnTheRows() {
        withTeam(()->{
            var fixture=new Fixture(0);
            when(((EmbeddingSpace.IdentifiedModel)fixture.model).embeddingSpace(3)).thenReturn(fixture.space,
                    new EmbeddingSpace("model:tag","b".repeat(64),3,8192));
            assertThrows(IllegalStateException.class,()->fixture.tools.semantic_search("query",null,10));
        });
    }
    private static void withTeam(Runnable action) {
        var previous=SecurityContextHolder.getContext();
        var context=SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("team","",List.of()));
        SecurityContextHolder.setContext(context);
        try {action.run();}finally {SecurityContextHolder.setContext(previous);}
    }
    private static final class Fixture {
        final EmbeddingSpace space=new EmbeddingSpace("model:tag","a".repeat(64),3,8192);
        final EmbeddingModel model=mock(EmbeddingModel.class,withSettings().extraInterfaces(EmbeddingSpace.IdentifiedModel.class));
        final JdbcTemplate jdbc=mock(JdbcTemplate.class);
        final CodeContextTools tools;
        Fixture(int stale) {
            when(((EmbeddingSpace.IdentifiedModel)model).embeddingSpace(3)).thenReturn(space);
            when(model.embed("query")).thenReturn(new float[]{1,0,0});
            when(jdbc.queryForObject(anyString(),eq(Integer.class),eq("team"),eq(space.fingerprint()))).thenReturn(stale);
            when(jdbc.queryForList(anyString(),eq("[1.0,0.0,0.0]"),eq("team"),eq(space.fingerprint()),eq("[1.0,0.0,0.0]"))).thenReturn(List.of(Map.of("file_path","Money.java")));
            var properties=mock(CodeContextProperties.class);when(properties.embeddingDimensions()).thenReturn(3);
            tools=new CodeContextTools(null,null,jdbc,model,null,properties,null,null,null,JsonMapper.builder().build(),null);
        }
    }
}
