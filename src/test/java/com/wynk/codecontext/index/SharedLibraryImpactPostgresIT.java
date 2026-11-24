package com.wynk.codecontext.index;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.wynk.codecontext.CodeContextApplication;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.repo.RepositoryStore;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.flywaydb.core.Flyway;
import com.wynk.codecontext.search.ZoektIndexer;

import javax.tools.ToolProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/** Real PostgreSQL acceptance test. Only external search and embedding services are replaced. */
@SpringBootTest(classes = CodeContextApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SharedLibraryImpactPostgresIT {
    private static final Path ROOT = Path.of("target/postgres-shared-library-it").toAbsolutePath().normalize();
    private static final String TEAM = "integration";
    private static final String API_KEY = "integration-token";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired RepositoryStore repositories;
    @Autowired PostgresContractIndexStore contractIndexes;
    @Autowired IndexingService indexing;
    @Autowired JsonMapper json;
    @MockitoBean EmbeddingModel embeddingModel;
    @MockitoBean ZoektIndexer zoektIndexer;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/code_context_test"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DATABASE_USER", "code_context"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("DATABASE_PASSWORD", "integration_test_password"));
        registry.add("code-context.repository-root", () -> ROOT.resolve("mirrors").toString());
        registry.add("code-context.local-git-root", () -> ROOT.resolve("git-sources").toString());
        registry.add("code-context.zoekt-index-root", () -> ROOT.resolve("zoekt").toString());
        registry.add("code-context.api-keys", () -> TEAM + "=" + API_KEY);
        registry.add("code-context.admin-api-keys", () -> "admin=admin-token");
        registry.add("code-context.graphify.enabled", () -> "false");
        registry.add("code-context.mcp-allowed-origins", () -> "http://localhost,http://127.0.0.1");
    }

    @BeforeAll
    static void prepareDirectories() throws Exception {
        Files.createDirectories(ROOT.resolve("git-sources"));
        Path artifacts = ROOT.resolve("maven-repository");
        System.setProperty("MAVEN_ALLOWED_REPOSITORIES", "integration=" + artifacts.toUri());
        System.setProperty("MAVEN_ALLOWED_HOSTS", "");
    }

    @BeforeEach
    void resetDatabaseAndExternalAdapters() throws Exception {
        String databaseName=jdbc.queryForObject("SELECT current_database()",String.class);
        if(!"code_context_test".equals(databaseName))throw new IllegalStateException("Refusing to clear non-test PostgreSQL database: "+databaseName);
        jdbc.update("DELETE FROM cross_repo_impact_analyses");
        jdbc.update("DELETE FROM contract_links");
        jdbc.update("DELETE FROM semantic_call_edges");
        jdbc.update("DELETE FROM semantic_call_sites");
        jdbc.update("DELETE FROM semantic_symbols");
        jdbc.update("DELETE FROM code_edges");
        jdbc.update("DELETE FROM contracts");
        jdbc.update("DELETE FROM contract_index_metadata");
        jdbc.update("DELETE FROM generation_files");
        jdbc.update("DELETE FROM code_chunks");
        jdbc.update("DELETE FROM code_nodes");
        jdbc.update("DELETE FROM maven_module_dependencies");
        jdbc.update("DELETE FROM maven_module_classpaths");
        jdbc.update("DELETE FROM index_jobs");
        jdbc.update("DELETE FROM index_generations");
        jdbc.update("DELETE FROM team_repository_access");
        jdbc.update("DELETE FROM repositories");
        if(Files.exists(ROOT))try(var paths=Files.walk(ROOT)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception e){throw new IllegalStateException(e);}});}
        Files.createDirectories(ROOT.resolve("git-sources"));
        System.setProperty("MAVEN_ALLOWED_REPOSITORIES", "integration=" + ROOT.resolve("maven-repository").toUri());
        when(embeddingModel.embed(anyString())).thenReturn(new float[1024]);
    }

    @Test
    void indexesGitRepositoriesPersistsV4V5V6AndReturnsExactImpactThroughMcp() throws Exception {
        assertEquals(0, flyway.migrate().migrationsExecuted, "restarting against an existing migrated database must be a no-op");
        assertEquals("6", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname='vector')", Boolean.class)));

        Path artifactRepository = ROOT.resolve("maven-repository");
        publishMoneyArtifact(artifactRepository, "1.0", "public String convert(String value) { return value; }\n    public String convert(Integer value) { return value.toString(); }");
        publishMoneyArtifact(artifactRepository, "2.0", "public String convert(java.math.BigDecimal value) { return value.toString(); }\n    public String convert(Integer value) { return value.toString(); }");

        Path providerSource = ROOT.resolve("git-sources/common-library");
        createGitRepository(providerSource, libraryFiles("1.0", "public String convert(String value) { return value; }\n    public String convert(Integer value) { return value.toString(); }"));
        onboard("common-library", providerSource, "LIBRARY");

        Path consumerASource = ROOT.resolve("git-sources/consumer-service-a");
        createGitRepository(consumerASource, consumerFiles("1.0", "ConsumerA", "String", "money.convert(\"x\")"));
        onboard("consumer-service-a", consumerASource, "SERVICE");
        Path consumerBSource = ROOT.resolve("git-sources/consumer-service-b");
        createGitRepository(consumerBSource, consumerFiles("2.0", "ConsumerB", "java.math.BigDecimal", "money.convert(java.math.BigDecimal.ONE)"));
        onboard("consumer-service-b", consumerBSource, "SERVICE");
        Path consumerCSource = ROOT.resolve("git-sources/consumer-service-c");
        createGitRepository(consumerCSource, consumerFiles("2.0", "ConsumerC", "Integer", "money.convert(Integer.valueOf(1))"));
        onboard("consumer-service-c", consumerCSource, "SERVICE");

        indexAndAwait("common-library");
        indexAndAwait("consumer-service-a");
        indexAndAwait("consumer-service-b");
        indexAndAwait("consumer-service-c");

        UUID consumerA = repositories.id("Fixture", "consumer-service-a");
        UUID consumerB = repositories.id("Fixture", "consumer-service-b");
        UUID consumerC = repositories.id("Fixture", "consumer-service-c");
        assertCallPersisted(consumerA, "org.acme.Money#convert(java.lang.String)", "src/main/java/app/ConsumerA.java");
        assertCallPersisted(consumerB, "org.acme.Money#convert(java.math.BigDecimal)", "src/main/java/app/ConsumerB.java");
        assertCallPersisted(consumerC, "org.acme.Money#convert(java.lang.Integer)", "src/main/java/app/ConsumerC.java");
        assertEquals("1.0", dependencyVersion(consumerA));
        assertEquals("2.0", dependencyVersion(consumerB));
        assertEquals("2.0", dependencyVersion(consumerC));
        var reloadedConsumerA = contractIndexes.published(consumerA).orElseThrow().index();
        var reloadedMavenImport = reloadedConsumerA.imports().stream()
                .filter(entry -> entry.type() == ContractType.MAVEN)
                .findFirst().orElseThrow();
        assertEquals("maven:org.acme:common-library", reloadedMavenImport.canonicalKey());
        assertEquals("1.0", reloadedMavenImport.dependencyVersion());
        assertTrue(reloadedMavenImport.metadata().containsKey("evidenceComplete"));

        write(providerSource, "pom.xml", pom("2.0", null).replace("fixture","org.acme"));
        write(providerSource, "src/main/java/org/acme/Money.java", librarySource("public String convert(java.math.BigDecimal value) { return value.toString(); }\n    public String convert(Integer value) { return value.toString(); }"));
        try (Git providerGit = Git.open(providerSource.toFile())) { commit(providerGit, "publish common-library 2.0"); }
        indexAndAwait("common-library");

        UUID provider = repositories.id("Fixture", "common-library");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=?", Integer.class, provider));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=? AND published", Integer.class, provider));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM maven_module_classpaths cp JOIN index_generations g ON g.id=cp.generation_id WHERE cp.repository_id=? AND cp.version='1.0' AND g.published=false",Integer.class,provider));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM maven_module_classpaths WHERE repository_id=? AND resolution_status='RESOLVED' AND version='2.0'", Integer.class, provider) > 0);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM maven_module_dependencies WHERE generation_id IN (SELECT generation_id FROM maven_module_classpaths WHERE repository_id=?) AND artifact_id='common-library' AND version IN ('1.0','2.0')", Integer.class, consumerA) > 0);

        JsonNode mcpResult = mcpImpactCall("Fixture/common-library");
        assertFalse(mcpResult.path("isError").asBoolean(false), mcpResult.toString());
        JsonNode payload = structuredToolPayload(mcpResult);
        assertTrue(payload.has("semanticMethodImpacts"),payload.toString());
        assertFalse(payload.path("blocking").asBoolean(true),payload.toString());
        JsonNode impacts=payload.path("semanticMethodImpacts");
        assertEquals(1,impacts.size(),impacts.toString());
        JsonNode impact=impacts.get(0);
        assertEquals("Fixture/common-library",impact.path("providerRepository").asText());
        assertEquals("Fixture/consumer-service-a",impact.path("consumerRepository").asText());
        assertEquals("app.ConsumerA",impact.path("callerClass").asText());
        assertEquals("call(org.acme.Money)",impact.path("callerMethod").asText());
        assertEquals("src/main/java/app/ConsumerA.java",impact.path("filePath").asText());
        assertEquals(5,impact.path("startLine").asInt());
        assertEquals("org.acme:common-library:1.0",impact.path("dependencyArtifact").asText());
        assertEquals("1.0",impact.path("dependencyVersion").asText());
        assertEquals("POTENTIAL_UPGRADE_IMPACT",impact.path("classification").asText());
        assertEquals("CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN",impact.path("currentCompatibility").asText());
        assertEquals("DIRECT",impact.path("relationship").asText());
        assertEquals("RESOLVED",impact.path("resolutionState").asText());
        assertEquals("CURRENT",impact.path("freshness").asText());
        assertEquals("PUBLISHED_V5_EDGE",impact.path("analysisCompleteness").asText());
        assertTrue(impact.path("confidence").asDouble()>0.0);
        assertFalse(impact.path("evidence").asText().isBlank());
        assertTrue(impact.path("provenance").asText().contains("MAVEN_RESOLVER"));
        assertFalse(impact.path("blocking").asBoolean(true));
        assertTrue(impact.path("providerOldApiSignature").asText().contains("convert(String"));
        assertTrue(impact.path("providerNewApiSignature").asText().contains("convert(java.math.BigDecimal"));
        assertTrue(impact.path("providerCommit").asText().matches("[0-9a-f]{40}"));
        assertTrue(impact.path("consumerCommit").asText().matches("[0-9a-f]{40}"));
        assertFalse(impacts.toString().contains("consumer-service-b"));
        assertFalse(impacts.toString().contains("consumer-service-c"));

        String persistedImpact = jdbc.queryForObject("SELECT result::text FROM cross_repo_impact_analyses WHERE team=? AND origin_repository_id=? ORDER BY created_at DESC LIMIT 1", String.class, TEAM, provider);
        assertNotNull(persistedImpact);
        assertTrue(persistedImpact.contains("Fixture/consumer-service-a"), persistedImpact);
        assertTrue(persistedImpact.contains("ConsumerA"), persistedImpact);
        assertTrue(persistedImpact.contains("consumer-service-a/src/main/java/app/ConsumerA.java"), persistedImpact);
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM semantic_call_edges e JOIN semantic_call_sites s ON s.id=e.call_site_id JOIN index_generations g ON g.id=e.generation_id AND g.published WHERE e.repository_id=? AND s.resolution_status='RESOLVED')", Boolean.class, consumerA)));
        assertTrue(jdbc.queryForObject("""
SELECT count(*) FROM maven_module_dependencies d JOIN maven_module_classpaths cp
 ON cp.generation_id=d.generation_id AND cp.module_path=d.module_path
WHERE cp.repository_id=? AND d.group_id='org.acme' AND d.artifact_id='common-library'
 AND d.version='1.0' AND d.scope='compile' AND d.artifact_path IS NOT NULL
 AND length(d.artifact_sha256)=64
""", Integer.class, consumerA) > 0);

        UUID lastPublished = repositories.currentGeneration(provider).orElseThrow();
        write(providerSource,"src/main/java/org/acme/ReleaseMarker.java","package org.acme; public final class ReleaseMarker {}\n");
        try (Git source = Git.open(providerSource.toFile())) { commit(source,"exercise failed indexing publication boundary"); }
        doThrow(new java.io.IOException("integration failure before publication"))
                .when(zoektIndexer).index(any(RepositoryRecord.class),any(Path.class),any(UUID.class));
        String failedCommit;
        try (Git source = Git.open(providerSource.toFile())) { failedCommit=source.getRepository().resolve("HEAD").name(); }
        UUID failedJob=indexing.enqueue("Fixture","common-library",failedCommit,"failure-boundary-"+failedCommit);
        indexing.poll();
        awaitFailed(failedJob);
        assertEquals(lastPublished,repositories.currentGeneration(provider).orElseThrow(),"failed indexing must retain the last successful generation");
        assertEquals(3,jdbc.queryForObject("SELECT attempt FROM index_jobs WHERE id=?",Integer.class,failedJob));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=? AND commit_sha=? AND published=false",Integer.class,provider,failedCommit)>0);
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("""
SELECT EXISTS(SELECT 1 FROM semantic_call_edges edge JOIN semantic_call_sites site ON site.id=edge.call_site_id
JOIN index_generations g ON g.id=edge.generation_id AND g.published
WHERE edge.repository_id=? AND edge.generation_id=site.generation_id AND site.commit_sha=?
AND edge.resolution_status='RESOLVED' AND site.resolved_signature='convert(java.lang.String)')
""",Boolean.class,consumerA,dependencyCommit(consumerA))));
    }

    private void assertCallPersisted(UUID repository, String targetKey, String path) {
        Integer rows = jdbc.queryForObject("""
SELECT count(*) FROM semantic_call_sites site
JOIN semantic_call_edges edge ON edge.call_site_id=site.id
JOIN semantic_symbols target ON target.id=edge.target_symbol_id
JOIN index_generations g ON g.id=site.generation_id AND g.published
WHERE site.repository_id=? AND site.file_path=? AND site.resolution_status='RESOLVED'
AND target.symbol_key=? AND edge.resolution_status='RESOLVED'
""", Integer.class, repository, path, targetKey);
        assertTrue(rows != null && rows > 0, "missing persisted V4/V5 resolved target " + targetKey + " in " + path);
        if (targetKey.endsWith("java.lang.String)")) {
            assertEquals(5, jdbc.queryForObject("SELECT start_line FROM semantic_call_sites WHERE repository_id=? AND file_path=? AND declaring_type='org.acme.Money' AND resolved_signature='convert(java.lang.String)' ORDER BY id LIMIT 1", Integer.class, repository, path));
        }
        Integer caller = jdbc.queryForObject("""
SELECT count(*) FROM semantic_call_sites site JOIN semantic_symbols caller ON caller.id=site.caller_symbol_id
WHERE site.repository_id=? AND site.file_path=? AND caller.symbol_key LIKE 'app.Consumer%#call(%'
""", Integer.class, repository, path);
        assertTrue(caller != null && caller > 0, "missing persisted canonical caller method for " + path);
    }

    private String dependencyVersion(UUID repository) {
        return jdbc.queryForObject("SELECT dependency_version FROM semantic_call_sites WHERE repository_id=? AND resolution_status='RESOLVED' AND dependency_version IS NOT NULL ORDER BY id LIMIT 1", String.class, repository);
    }

    private String dependencyCommit(UUID repository) {
        return jdbc.queryForObject("SELECT commit_sha FROM index_generations WHERE repository_id=? AND published",String.class,repository);
    }

    private void onboard(String name, Path source, String type) {
        RepositoryRecord repo = repositories.insert("Fixture", name, source.toUri().toString(), TEAM, "main", type,
                ROOT.resolve("mirrors/Fixture").resolve(name).toString());
        jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES(?,?) ON CONFLICT DO NOTHING", TEAM, repo.id());
    }

    private void indexAndAwait(String name) throws Exception {
        String sha;
        try (Git source = Git.open(ROOT.resolve("git-sources").resolve(name).toFile())) { sha = source.getRepository().resolve("HEAD").name(); }
        UUID job = indexing.enqueue("Fixture", name, sha, "integration-" + name + "-" + sha);
        indexing.poll();
        long deadline = System.nanoTime() + java.time.Duration.ofMinutes(3).toNanos();
        while (System.nanoTime() < deadline) {
            var state = jdbc.queryForMap("SELECT state,error FROM index_jobs WHERE id=?", job);
            if ("SUCCEEDED".equals(state.get("state"))) return;
            if ("FAILED".equals(state.get("state"))) fail("indexing failed for " + name + ": " + state.get("error"));
            Thread.sleep(250);
        }
        fail("timed out waiting for index job for " + name);
    }

    private void awaitFailed(UUID job) throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(75).toNanos();
        while(System.nanoTime()<deadline) {
            var state=jdbc.queryForMap("SELECT state,error FROM index_jobs WHERE id=?",job);
            if("FAILED".equals(state.get("state")))return;
            if("SUCCEEDED".equals(state.get("state")))fail("failure injection unexpectedly published a generation");
            Thread.sleep(250);
        }
        fail("timed out waiting for failure-injected job to exhaust retries");
    }

    private static void createGitRepository(Path directory, Map<String,String> files) throws Exception {
        Files.createDirectories(directory);
        try (Git git = Git.init().setDirectory(directory.toFile()).setInitialBranch("main").call()) {
            files.forEach((path,content) -> write(directory,path,content));
            git.add().addFilepattern(".").call();
            git.commit().setMessage("initial fixture").setAuthor("Integration Test","integration@example.test").call();
        }
    }

    private static void commit(Git git, String message) throws Exception {
        git.add().addFilepattern(".").call();
        git.commit().setMessage(message).setAuthor("Integration Test","integration@example.test").call();
    }

    private static Map<String,String> libraryFiles(String version, String api) {
        return Map.of("pom.xml", pom(version, null).replace("fixture","org.acme"), "src/main/java/org/acme/Money.java", librarySource(api));
    }

    private static Map<String,String> consumerFiles(String version, String className, String returnType, String invocation) {
        return Map.of("pom.xml", pom("1.0", "<dependencies><dependency><groupId>org.acme</groupId><artifactId>common-library</artifactId><version>"+version+"</version></dependency></dependencies>"),
                "src/main/java/app/"+className+".java", "package app;\nimport org.acme.Money;\npublic class "+className+" {\n  public "+returnType+" call(Money money) {\n    return "+invocation+";\n  }\n}\n");
    }

    private static String pom(String version, String extra) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>"+(extra==null?"common-library":"consumer")+"</artifactId><version>"+version+"</version>"+(extra==null?"<packaging>jar</packaging>":extra)+"</project>";
    }

    private static String librarySource(String api) {
        return "package org.acme;\npublic class Money {\n    "+api+"\n}\n";
    }

    private static void publishMoneyArtifact(Path repository, String version, String api) throws Exception {
        Path artifactDir = repository.resolve("org/acme/common-library").resolve(version);
        Files.createDirectories(artifactDir);
        Path work = ROOT.resolve("compiled-library").resolve(version);
        Path source = work.resolve("src/org/acme/Money.java");
        Path classes = work.resolve("classes");
        Files.createDirectories(source.getParent()); Files.createDirectories(classes);
        Files.writeString(source, librarySource(api));
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),source.toString()));
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(artifactDir.resolve("common-library-"+version+".jar")));
             var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\','/')));
                Files.copy(file,jar); jar.closeEntry();
            }
        }
        Files.writeString(artifactDir.resolve("common-library-"+version+".pom"),pom(version,null).replace("fixture","org.acme"));
    }

    private JsonNode mcpImpactCall(String repository) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://localhost:"+port+"/mcp");
        Object initialize=Map.of("jsonrpc","2.0","id",1,"method","initialize","params",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","postgres-impact-it","version","1.0")));
        HttpResponse<String> unauthenticated=exchange(client,endpoint,null,initialize,null);
        assertEquals(401,unauthenticated.statusCode(),"MCP must reject an unauthenticated client");
        JsonNode initialized = post(client,endpoint,null,initialize);
        assertTrue(initialized.path("result").has("serverInfo"), initialized.toString());
        HttpResponse<String> initResponse = lastResponse;
        String session = initResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(session,"Streamable HTTP initialize must return a session identifier");
        post(client,endpoint,session,Map.of("jsonrpc","2.0","method","notifications/initialized"));
        JsonNode tools = post(client,endpoint,session,Map.of("jsonrpc","2.0","id",2,"method","tools/list","params",Map.of()));
        for(String name:List.of("search_code","read_file","find_symbol","get_neighbors","semantic_search","impact_analysis"))
            assertTrue(tools.toString().contains(name),tools.toString());
        return post(client,endpoint,session,Map.of("jsonrpc","2.0","id",3,"method","tools/call","params",Map.of("name","impact_analysis","arguments",Map.of("contract","common-library","repository",repository))));
    }

    private HttpResponse<String> lastResponse;
    private String protocolVersion;
    private JsonNode post(HttpClient client, URI endpoint, String session, Object body) throws Exception {
        HttpResponse<String> response=exchange(client,endpoint,session,body,API_KEY);
        assertTrue(response.statusCode()>=200&&response.statusCode()<300,"MCP HTTP "+response.statusCode()+": "+response.body());
        String payload=response.body().lines().filter(line->line.startsWith("data:")).map(line->line.substring(5).strip()).reduce((first,last)->last).orElse(response.body());
        if(payload.isBlank())return json.createObjectNode();
        return json.readTree(payload);
    }

    private HttpResponse<String> exchange(HttpClient client,URI endpoint,String session,Object body,String apiKey)throws Exception {
        var builder = HttpRequest.newBuilder(endpoint).header(HttpHeaders.CONTENT_TYPE,MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT,"application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if(apiKey!=null)builder.header("X-API-Key",apiKey);
        if(session!=null)builder.header("Mcp-Session-Id",session);
        if(session!=null&&protocolVersion!=null)builder.header("Mcp-Protocol-Version",protocolVersion);
        lastResponse=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        lastResponse.headers().firstValue("Mcp-Protocol-Version").ifPresent(value->protocolVersion=value);
        return lastResponse;
    }

    private JsonNode structuredToolPayload(JsonNode response)throws Exception {
        JsonNode structured=response.path("result").path("structuredContent");
        if(structured.isObject())return structured;
        String text=response.path("result").path("content").path(0).path("text").asText();
        assertFalse(text.isBlank(),"MCP tool returned neither structuredContent nor JSON text: "+response);
        return json.readTree(text);
    }

    private static void write(Path root,String path,String content) {
        try { Path output=root.resolve(path);Files.createDirectories(output.getParent());Files.writeString(output,content); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
