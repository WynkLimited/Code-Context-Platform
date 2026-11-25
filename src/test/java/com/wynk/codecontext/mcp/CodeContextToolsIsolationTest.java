package com.wynk.codecontext.mcp;

import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.repo.RepositoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CodeContextToolsIsolationTest {
    @Test void deniedRepositoryDoesNotInvokeEmbeddingModelOrDatabaseSearch() {
        var foreign=new RepositoryRecord(UUID.randomUUID(),"foreign","library","","foreign-team","main","LIBRARY","");
        var store=new RepositoryStore(new JdbcTemplate()) {
            @Override public Optional<RepositoryRecord> find(String owner,String name){return Optional.of(foreign);}
            @Override public List<RepositoryRecord> findAccessible(String team){return List.of();}
        };
        var tools=new CodeContextTools(null,store,null,null,null,null,null,null,null,null,null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("isolated","",List.of()));
        try {
            // Missing model/JDBC deliberately makes any pre-authorization work fail this test.
            assertThrows(SecurityException.class,()->tools.semantic_search("query","foreign/library",10));
            assertThrows(SecurityException.class,()->tools.get_neighbors("foreign/library","Secret"));
            assertThrows(SecurityException.class,()->tools.read_file("foreign/library","Secret.java",1,10));
            assertThrows(SecurityException.class,()->tools.impact_analysis("","foreign/library"));
        } finally {SecurityContextHolder.clearContext();}
    }
}
