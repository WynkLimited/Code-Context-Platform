package com.wynk.codecontext.index;

import com.wynk.codecontext.CodeContextApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the pinned Graphify CLI and PostgreSQL graph import path against real source files. */
@SpringBootTest(classes = CodeContextApplication.class)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GraphifyPostgresIT {
    private static final String ROOT = System.getProperty("java.io.tmpdir") + "/ccp-graphify-it";

    @Autowired GraphifyIndexer graphify;
    @Autowired JdbcTemplate jdbc;
    private UUID repository;
    private UUID generation;
    private Path fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/code_context_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DATABASE_USER", "code_context"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("DATABASE_PASSWORD", "integration_test_password"));
        registry.add("code-context.graphify.enabled", () -> true);
        registry.add("code-context.graphify.command", () -> System.getenv().getOrDefault("GRAPHIFY_COMMAND", "graphify"));
    }

    @Test
    void runsPinnedGraphifyCliAndPersistsGenerationBoundNodesAndEdges() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        fixture = Files.createTempDirectory("ccp-graphify-source-");
        Path service = fixture.resolve("src/main/java/example/OrderService.java");
        Path order = fixture.resolve("src/main/java/example/Order.java");
        Files.createDirectories(service.getParent());
        Files.writeString(order, "package example; public class Order { public int total() { return 7; } }");
        Files.writeString(service, "package example; public class OrderService { public int load(Order order) { return order.total(); } }");

        repository = jdbc.queryForObject("""
                INSERT INTO repositories(owner,name,clone_url,team,default_branch,repository_type,mirror_path)
                VALUES(?,?,'https://github.com/fixture/graphify.git','graphify-it','main','SERVICE',?) RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), "fixture-" + suffix, "graphify-" + suffix, fixture.toString());
        generation = jdbc.queryForObject("""
                INSERT INTO index_generations(repository_id,commit_sha,generation,published)
                VALUES(?,repeat('a',40),1,false) RETURNING id
                """, (rs, row) -> rs.getObject(1, UUID.class), repository);

        assertTrue(graphify.buildAndPersist(fixture, generation), "Graphify must be enabled in this integration test");
        assertTrue(Files.isRegularFile(fixture.resolve("graphify-out/graph.json")), "Graphify must produce its actual JSON graph output");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=?", Integer.class, generation) > 0,
                "Graphify nodes must be persisted for the candidate generation");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_edges WHERE generation_id=?", Integer.class, generation) > 0,
                "Graphify relationships must be persisted for the candidate generation");
    }

    @AfterEach
    void cleanFixture() throws Exception {
        if (generation != null) jdbc.update("DELETE FROM index_generations WHERE id=?", generation);
        if (repository != null) jdbc.update("DELETE FROM repositories WHERE id=?", repository);
        if (fixture != null && Files.exists(fixture)) {
            try (var paths = Files.walk(fixture)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception failure) { throw new IllegalStateException(failure); }
                });
            }
        }
    }
}
