package com.wynk.codecontext.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wynk.codecontext.config.CodeContextProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphifyIndexerTest {
    @TempDir Path temp;

    @Test void importsGraphifyLocationsAndEdgesFromActualCliJsonShape() throws Exception {
        Path command = temp.resolve("graphify-fixture");
        Files.writeString(command, """
                #!/bin/sh
                mkdir -p graphify-out
                cat > graphify-out/graph.json <<'JSON'
                {"nodes":[
                  {"id":"file-id","label":"Money.java","file_type":"code","source_file":"src/Money.java","source_location":"L1"},
                  {"id":"symbol-id","label":"Money","type":"class","source_file":"src/Money.java","source_location":"L5"}
                ],"links":[
                  {"source":"file-id","target":"symbol-id","relation":"contains","confidence":"EXTRACTED","source_location":"L5"}
                ]}
                JSON
                """);
        assertTrue(command.toFile().setExecutable(true));
        var properties = new CodeContextProperties(temp.resolve("repos").toString(), temp.toString(),
                temp.resolve("zoekt").toString(), "team=team-secret", "admin=admin-secret", "webhook-secret", "",
                "github.com", "http://localhost", 1024, 1024 * 1024, 6000, 2, 300,
                new CodeContextProperties.Zoekt("zoekt-git-index", "http://localhost:6070"),
                new CodeContextProperties.Graphify(command.toString(), true));
        CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
        var indexer = new GraphifyIndexer(properties, new ObjectMapper(), jdbc);

        assertTrue(indexer.buildAndPersist(temp, UUID.randomUUID()));

        assertEquals(2, jdbc.nodes.size());
        assertEquals(5, jdbc.nodes.get(1)[5]);
        assertEquals(1, jdbc.edges.size());
        assertEquals(71L, jdbc.edges.get(0)[1]);
        assertEquals(72L, jdbc.edges.get(0)[2]);
        assertEquals("contains", jdbc.edges.get(0)[3]);
        assertEquals(0.8d, jdbc.edges.get(0)[5]);
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private final List<Object[]> nodes = new ArrayList<>();
        private final List<Object[]> edges = new ArrayList<>();
        @Override public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            nodes.add(args);
            return requiredType.cast(Long.valueOf(70 + nodes.size()));
        }
        @Override public int update(String sql, Object... args) {
            if (sql.contains("INSERT INTO code_edges")) edges.add(args);
            return 1;
        }
    }
}
