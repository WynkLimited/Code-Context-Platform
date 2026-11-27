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
@SpringBootTest(classes = CodeContextApplication.class, webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class GraphifyPostgresIT {
    private static final String ROOT = System.getProperty("java.io.tmpdir") + "/ccp-graphify-it";

    @Autowired GraphifyIndexer graphify;
    @Autowired JdbcTemplate jdbc;
    @Autowired IndexingService indexing;
    @Autowired RepositoryIndexLeaseManager leases;
    @Autowired GenerationRetentionService retention;
    @Autowired com.wynk.codecontext.config.CodeContextProperties config;
    @Autowired com.wynk.codecontext.repo.RepositoryStore repositories;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean org.springframework.ai.embedding.EmbeddingModel embeddings;
    @Autowired tools.jackson.databind.json.JsonMapper json;
    private UUID repository;
    private UUID generation;
    private Path fixture;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/code_context_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DATABASE_USER", "code_context"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("DATABASE_PASSWORD", "integration_test_password"));
        registry.add("code-context.repository-root",()->Path.of(ROOT,"mirrors").toString());
        registry.add("code-context.local-git-root",()->Path.of(ROOT,"sources").toString());
        registry.add("code-context.zoekt-index-root",()->System.getenv().getOrDefault("ZOEKT_INDEX_ROOT",Path.of(ROOT,"index").toString()));
        registry.add("code-context.zoekt.indexer",()->System.getenv().getOrDefault("ZOEKT_INDEXER","zoekt-git-index"));
        registry.add("code-context.zoekt.webserver",()->System.getenv().getOrDefault("ZOEKT_WEB_SERVER","http://localhost:6070"));
        registry.add("spring.ai.ollama.base-url",()->System.getenv().getOrDefault("OLLAMA_BASE_URL","http://localhost:11434"));
        registry.add("code-context.api-keys",()->"graphify=graphify-semantic-test-token");
        registry.add("code-context.admin-api-keys",()->"graphify=graphify-onboarding-admin-token");
        registry.add("code-context.mcp-allowed-origins",()->"http://localhost,http://127.0.0.1");
        registry.add("CONTRACT_REFRESH_POLL_MS",()->"3600000");
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

        Path python=fixture.resolve("samples/refund_policy.py");Files.createDirectories(python.getParent());
        Files.writeString(python,"class RefundPolicy:\n    def eligible(self, paid):\n        return paid > 0\n");
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
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path LIKE '%refund_policy.py'",Integer.class,generation)>0,
                "the real pinned Graphify must extract the same Python structural fixture family");
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_edges WHERE generation_id=?", Integer.class, generation) > 0,
                "Graphify relationships must be persisted for the candidate generation");
    }

    @Test
    void indexesStructuralChunksThroughGraphifyOllamaPostgresAndHttpMcpThenReusesAndDeletesVectors() throws Exception {
        fixture=Files.createTempDirectory(Files.createDirectories(Path.of(ROOT,"sources")),"semantic-").toRealPath();
        String javaPath="src/main/java/fixtures/Eligibility.java";
        String pythonPath="samples/refund_policy.py";
        String javaSource="package fixtures;\npublic class Eligibility {\n    private int threshold = 5;\n    public int getLimit() { return threshold; }\n    public void setLimit(int value) { threshold = value; }\n    public boolean allowRefund(int amount) {\n        return amount > threshold;\n    }\n    public String explain() {\n"+
                java.util.stream.IntStream.rangeClosed(1,100).mapToObj(n->"        // Evidence line "+n+" "+"退款😀 ".repeat(12)).collect(java.util.stream.Collectors.joining("\n"))+"\n        return \"verified\";\n    }\n}\n";
        com.github.javaparser.StaticJavaParser.parse(javaSource); // The fixture itself must be valid Java.
        writeFixture(javaPath,javaSource);
        writeFixture(pythonPath,"class RefundPolicy:\n    def eligible(self, paid, cancelled):\n        return paid > 0 and not cancelled\n");
        writeFixture("src/main/kotlin/fixtures/Limits.kt","package fixtures\nclass Limits {\n    fun allowed(amount: Int): Boolean {\n        return amount > 0\n    }\n}\n");
        writeFixture("samples/Shipping.ts","class Shipping {\n    eligible(weight: number): boolean {\n        return weight < 30;\n    }\n}\n");
        String before;
        try(var git=org.eclipse.jgit.api.Git.init().setDirectory(fixture.toFile()).setInitialBranch("main").call()) {before=commitFixture(git,"structural baseline");}
        String name="semantic-"+UUID.randomUUID().toString().replace("-","");
        var record=automaticallyOnboard(name,before);
        generation=repositories.currentGeneration(repository).orElseThrow();
        UUID initial=generation;
        assertGraphifyProvenance("FULL_NO_VALID_BASELINE",null);
        var chunks=jdbc.queryForList("SELECT file_path,symbol,start_line,end_line,content_hash,content,embedding::text AS vector FROM code_chunks WHERE generation_id=?",generation);
        assertTrue(chunks.size()>10,"long Unicode method must really be subdivided before embedding");
        for(var chunk:chunks) {
            String input=chunk.get("content").toString();
            org.junit.jupiter.api.Assertions.assertTrue(input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length+32<=1500);
            org.junit.jupiter.api.Assertions.assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))),chunk.get("content_hash"));
            org.junit.jupiter.api.Assertions.assertNotNull(chunk.get("vector"));
        }
        org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=? AND symbol='getLimit() | setLimit(int) | allowRefund(int)' AND start_line=4 AND end_line=8",Integer.class,generation));
        var longChunks=jdbc.queryForList("SELECT start_line,end_line FROM code_chunks WHERE generation_id=? AND symbol='explain()'",generation);
        for(int line=9;line<=111;line++) {
            final int expectedLine=line;
            assertTrue(longChunks.stream().anyMatch(c->((Number)c.get("start_line")).intValue()<=expectedLine&&((Number)c.get("end_line")).intValue()>=expectedLine),"large method source line missing from persisted chunks: "+line);
        }
        for(var expected:java.util.Map.of(javaPath,"allowRefund",pythonPath,"eligible","src/main/kotlin/fixtures/Limits.kt","allowed","samples/Shipping.ts","eligible").entrySet()) {
            String path=expected.getKey(),method=expected.getValue();
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path=? AND jsonb_exists(metadata,'id')",Integer.class,generation,path)>0,"real Graphify must import "+path);
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path=? AND node_type='METHOD' AND symbol=? AND jsonb_exists(metadata,'blobSha')",Integer.class,generation,path,method)>0,"native method declaration must be persisted for "+path);
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=? AND file_path=? AND symbol LIKE ?",Integer.class,generation,path,"%"+method+"(%")>0,"the actual method, not just a class header or fallback, must be embedded for "+path);
        }
        org.junit.jupiter.api.Assertions.assertEquals(EmbeddingSpace.current(embeddings,config.embeddingDimensions()).fingerprint(),jdbc.queryForObject("SELECT embedding_fingerprint FROM index_generations WHERE id=?",String.class,generation));
        var mcp=new SemanticMcp();mcp.initialize();
        var answers=mcp.search(record.key());
        assertTrue(answers.toString().contains(pythonPath),"natural-language cancellation/refund question must retrieve its labelled implementation: "+answers);
        assertTrue(answers.toString().contains(before),"HTTP MCP must expose the indexed commit");
        writeFixture(javaPath,javaSource.replace("return threshold;","return threshold + 1;"));
        String after;try(var git=org.eclipse.jgit.api.Git.open(fixture.toFile())){after=commitFixture(git,"edit one tiny member");}
        org.mockito.Mockito.clearInvocations(embeddings);
        generation=indexFixture(name,after);
        assertGraphifyProvenance("INCREMENTAL_AST_GLOBAL_RELINK",initial);
        assertCleanGraphEquivalent();
        org.mockito.Mockito.verify(embeddings,org.mockito.Mockito.times(1)).embed(org.mockito.ArgumentMatchers.anyString());
        org.junit.jupiter.api.Assertions.assertEquals(chunks.size()-1,jdbc.queryForObject("SELECT count(*) FROM code_chunks a JOIN code_chunks b ON a.file_path=b.file_path AND a.content_hash=b.content_hash AND a.content=b.content AND a.embedding=b.embedding WHERE a.generation_id=? AND b.generation_id=?",Integer.class,initial,generation),"only the changed merged input may receive a new vector");
        UUID edited=generation;
        Files.delete(fixture.resolve(pythonPath));
        String deleted;try(var git=org.eclipse.jgit.api.Git.open(fixture.toFile())){deleted=commitFixture(git,"remove Python implementation");}
        generation=indexFixture(name,deleted);
        assertGraphifyProvenance("INCREMENTAL_AST_GLOBAL_RELINK",edited);
        assertCleanGraphEquivalent();
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=? AND file_path=?",Integer.class,generation,pythonPath));
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path=?",Integer.class,generation,pythonPath));
        org.junit.jupiter.api.Assertions.assertFalse(mcp.search(record.key()).toString().contains(pythonPath),"deleted vectors must not remain searchable through the same real MCP session");
        UUID beforeMove=generation;
        String movedPath="samples/refunds/policy.ts";
        Files.createDirectories(fixture.resolve(movedPath).getParent());
        Files.move(fixture.resolve("samples/Shipping.ts"),fixture.resolve(movedPath));
        String moved;try(var git=org.eclipse.jgit.api.Git.open(fixture.toFile())){moved=commitFixture(git,"move TypeScript implementation");}
        generation=indexFixture(name,moved);
        assertGraphifyProvenance("INCREMENTAL_AST_GLOBAL_RELINK",beforeMove);
        assertCleanGraphEquivalent();
        org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path='samples/Shipping.ts'",Integer.class,generation));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path=?",Integer.class,generation,movedPath)>0);
        writeFixture(".gitignore","ignored-fixture-output/\n");
        String configured;try(var git=org.eclipse.jgit.api.Git.open(fixture.toFile())){configured=commitFixture(git,"change extraction inputs");}
        generation=indexFixture(name,configured);
        assertGraphifyProvenance("FULL_GLOBAL_INPUT_CHANGE",null);
        assertCleanGraphEquivalent();
    }
    private void assertGraphifyProvenance(String mode,UUID baseline)throws Exception {
        var provenance=json.readTree(jdbc.queryForObject("SELECT graphify_provenance::text FROM index_generations WHERE id=?",String.class,generation));
        org.junit.jupiter.api.Assertions.assertEquals(mode,provenance.path("mode").asText(),provenance.toString());
        if(baseline!=null) {
            org.junit.jupiter.api.Assertions.assertEquals(baseline.toString(),provenance.path("baselineGeneration").asText());
            assertTrue(provenance.path("reportedCachedFiles").asInt()>0,"native CLI must actually reuse AST extraction: "+provenance);
            assertTrue(provenance.path("sourceFilesReused").asInt()>0,"unchanged inputs must be reused: "+provenance);
        }
    }
    private void assertCleanGraphEquivalent()throws Exception {
        Path clean=Files.createTempDirectory("ccp-graphify-full-equivalence-");
        UUID cold=jdbc.queryForObject("""
                INSERT INTO index_generations(repository_id,commit_sha,generation,published,repository_revision)
                SELECT repository_id,commit_sha,(SELECT max(generation)+1 FROM index_generations WHERE repository_id=?),false,repository_revision
                FROM index_generations WHERE id=? RETURNING id
                """,UUID.class,repository,generation);
        try {
            try(var paths=Files.walk(fixture)) {
                for(Path source:paths.filter(Files::isRegularFile).filter(path->!fixture.relativize(path).startsWith(".git")).toList()) {
                    Path target=clean.resolve(fixture.relativize(source));Files.createDirectories(target.getParent());Files.copy(source,target);
                }
            }
            assertTrue(graphify.buildAndPersist(clean,cold));
            for(String table:java.util.List.of("code_nodes","code_edges")) {
                // The pipeline also persists its native structural extractor alongside Graphify.
                // Compare exactly the Graphify-owned records, which carry native graph IDs.
                String sql=table.equals("code_nodes")
                        ?"SELECT metadata::text FROM code_nodes WHERE generation_id=? AND jsonb_exists(metadata,'id') ORDER BY metadata::text"
                        :"SELECT e.evidence FROM code_edges e JOIN code_nodes s ON s.id=e.source_node JOIN code_nodes t ON t.id=e.target_node WHERE e.generation_id=? AND jsonb_exists(s.metadata,'id') AND jsonb_exists(t.metadata,'id') ORDER BY e.evidence";
                org.junit.jupiter.api.Assertions.assertEquals(jdbc.queryForList(sql,String.class,cold),jdbc.queryForList(sql,String.class,generation),
                        "incremental "+table+" must exactly match a clean native extraction");
            }
        }finally {
            jdbc.update("DELETE FROM index_generations WHERE id=?",cold);
            try(var paths=Files.walk(clean)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private com.wynk.codecontext.model.RepositoryRecord automaticallyOnboard(String name,String commit)throws Exception {
        var client=java.net.http.HttpClient.newHttpClient();
        var request=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/repositories"))
                .header("X-API-Key","graphify-onboarding-admin-token").header("Content-Type","application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(java.util.Map.of(
                        "owner","fixture","name",name,"cloneUrl",fixture.toUri().toString(),"type","LIBRARY")))).build();
        var response=client.send(request,java.net.http.HttpResponse.BodyHandlers.ofString());
        org.junit.jupiter.api.Assertions.assertEquals(200,response.statusCode(),response.body());
        var record=json.readValue(response.body(),com.wynk.codecontext.model.RepositoryRecord.class);repository=record.id();
        UUID job=jdbc.queryForObject("SELECT initial_job_id FROM repository_onboarding WHERE repository_id=?",UUID.class,repository);
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(90).toNanos();
        while(System.nanoTime()<deadline) {
            var state=jdbc.queryForMap("SELECT state,error,commit_sha FROM index_jobs WHERE id=?",job);
            if("SUCCEEDED".equals(state.get("state"))) {
                org.junit.jupiter.api.Assertions.assertEquals(commit,state.get("commit_sha"));return record;
            }
            if("FAILED".equals(state.get("state")))org.junit.jupiter.api.Assertions.fail("automatic all-context indexing failed: "+state.get("error"));
            Thread.sleep(200);
        }
        throw new AssertionError("automatic all-context indexing did not publish before the existing deadline");
    }
    private void writeFixture(String path,String content)throws Exception {Path file=fixture.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,content);}
    private static String commitFixture(org.eclipse.jgit.api.Git git,String message)throws Exception {
        git.add().addFilepattern(".").call();git.add().setUpdate(true).addFilepattern(".").call();
        return git.commit().setMessage(message).setAuthor("Fixture","fixture@example.test").setCommitter("Fixture","fixture@example.test").call().getName();
    }
    private UUID indexFixture(String name,String commit)throws Exception {
        UUID job=indexing.enqueue("fixture",name,commit,"semantic-"+UUID.randomUUID());
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(90).toNanos();
        while(System.nanoTime()<deadline) {
            var row=jdbc.queryForMap("SELECT state,error,generation_id FROM index_jobs WHERE id=?",job);
            org.junit.jupiter.api.Assertions.assertNotEquals("FAILED",row.get("state"),"real structural indexing failed: "+row);
            if("SUCCEEDED".equals(row.get("state"))) {
                UUID id=(UUID)row.get("generation_id");org.junit.jupiter.api.Assertions.assertNotNull(id);
                org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE id=? AND repository_id=? AND commit_sha=? AND published",Integer.class,id,repository,commit));
                return id;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("real structural indexing exceeded the fixture deadline");
    }
    private final class SemanticMcp {
        final java.net.http.HttpClient client=java.net.http.HttpClient.newHttpClient();String session,protocol;int sequence=1;
        tools.jackson.databind.JsonNode request(Object body)throws Exception {
            var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/mcp"))
                    .timeout(java.time.Duration.ofSeconds(30)).header("Content-Type","application/json").header("Accept","application/json, text/event-stream")
                    .header("X-API-Key","graphify-semantic-test-token").POST(java.net.http.HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            if(session!=null)builder.header("Mcp-Session-Id",session).header("Mcp-Protocol-Version",protocol);
            var response=client.send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode()>=200&&response.statusCode()<300,response.body());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value->session=value);
            String payload=response.body().lines().filter(line->line.startsWith("data:")).map(line->line.substring(5).strip()).reduce((first,last)->last).orElse(response.body());
            return payload.isBlank()?json.createObjectNode():json.readTree(payload);
        }
        void initialize()throws Exception {
            var result=request(java.util.Map.of("jsonrpc","2.0","id",sequence++,"method","initialize","params",java.util.Map.of("protocolVersion","2025-03-26","capabilities",java.util.Map.of(),"clientInfo",java.util.Map.of("name","structural-semantic-it","version","1"))));
            protocol=result.path("result").path("protocolVersion").asText();org.junit.jupiter.api.Assertions.assertNotNull(session);assertTrue(!protocol.isBlank());
            request(java.util.Map.of("jsonrpc","2.0","method","notifications/initialized"));
        }
        tools.jackson.databind.JsonNode search(String repository)throws Exception {
            var response=request(java.util.Map.of("jsonrpc","2.0","id",sequence++,"method","tools/call","params",java.util.Map.of("name","semantic_search","arguments",java.util.Map.of("query","whether a paid transaction can be refunded when it has not been cancelled","repository",repository,"limit",10))));
            org.junit.jupiter.api.Assertions.assertFalse(response.path("result").path("isError").asBoolean(false),response.toString());
            var structured=response.path("result").path("structuredContent");
            return structured.isArray()?structured:json.readTree(response.path("result").path("content").path(0).path("text").asText());
        }
    }

    @AfterEach
    void cleanFixture() throws Exception {
        if(repository!=null) {
            // SUCCEEDED means publication is visible; post-publication shard cleanup may still
            // own the lease. Teardown must not race that worker or the global Zoekt cleaner.
            RepositoryIndexLeaseManager.Lease cleanupLease=null;
            long deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
            while(cleanupLease==null&&System.nanoTime()<deadline) {
                cleanupLease=leases.tryAcquire(repository,UUID.randomUUID()).orElse(null);
                if(cleanupLease==null)Thread.sleep(100);
            }
            org.junit.jupiter.api.Assertions.assertNotNull(cleanupLease,"publisher must release ownership before fixture purge");
            var generations=jdbc.queryForList("SELECT id FROM index_generations WHERE repository_id=?",UUID.class,repository);
            try(var owned=cleanupLease) {
                new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status->{owned.fenceTransaction();repositories.purge(repository);});
            }
            retention.removeRepositoryMetadata(repository);
            // Follow production's durable retry path for a busy global cleaner; do not suppress
            // an exception from a direct competing removeGeneration call.
            for(int i=0;i<generations.size();i++)if(!retention.cleanOne())break;
            for(UUID id:generations)org.junit.jupiter.api.Assertions.assertEquals(1,jdbc.queryForObject(
                    "SELECT count(*) FROM generation_cleanup_outbox WHERE generation_id=? AND state IN ('QUEUED','DONE')",Integer.class,id),"every fixture shard must have durable cleanup ownership");
            org.junit.jupiter.api.Assertions.assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM repositories WHERE id=?",Integer.class,repository));
        }
        if (fixture != null && Files.exists(fixture)) {
            try (var paths = Files.walk(fixture)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); } catch (Exception failure) { throw new IllegalStateException(failure); }
                });
            }
        }
    }
}
