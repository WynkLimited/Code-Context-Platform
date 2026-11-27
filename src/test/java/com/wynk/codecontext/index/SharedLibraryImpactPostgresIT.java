package com.wynk.codecontext.index;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.wynk.codecontext.CodeContextApplication;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.repo.RepositoryStore;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.port.contract.ContractModels.ContractType;
import com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.CallSite;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/** Real PostgreSQL, Zoekt, and Ollama acceptance test. */
@SpringBootTest(classes = CodeContextApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
// Later separate-JVM fixtures use isolated PostgreSQL schemas with the same real Zoekt service.
// Do not leave this catalog's background orphan cleanup running against their shards.
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@org.springframework.test.context.TestExecutionListeners(listeners=SharedLibraryImpactPostgresIT.SchemaHandoff.class,
        mergeMode=org.springframework.test.context.TestExecutionListeners.MergeMode.MERGE_WITH_DEFAULTS)
class SharedLibraryImpactPostgresIT {
    private static final String FIXTURE=UUID.randomUUID().toString().replace("-","");
    private static final String SCHEMA="shared_impact_"+FIXTURE;
    private static final Path ROOT = Path.of("target/postgres-shared-library-it",FIXTURE).toAbsolutePath().normalize();
    private static final String TEAM = "integration";
    private static final String API_KEY = "integration-token";

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired RepositoryStore repositories;
    @Autowired PostgresContractIndexStore contractIndexes;
    @Autowired IndexingService indexing;
    @Autowired RepositoryIndexLeaseManager repositoryIndexLeases;
    @Autowired ConsumerContractIndexRefresher contractRefresher;
    @Autowired JsonMapper json;
    @Autowired com.wynk.codecontext.config.CodeContextProperties config;
    @MockitoSpyBean ZoektIndexer zoektIndexer;
    @MockitoSpyBean org.springframework.ai.embedding.EmbeddingModel embeddingModel;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // Keep durable refresh jobs queued during this scenario so the late-provider assertion
        // proves relationship binding does not depend on reindexing the consumer generation.
        registry.add("CONTRACT_REFRESH_POLL_MS", () -> "3600000");
        registry.add("REPOSITORY_REFRESH_POLL_MS", () -> "100");
        String base=System.getenv().getOrDefault("DATABASE_URL", "jdbc:postgresql://localhost:5432/code_context_test");
        var admin=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(base,
                System.getenv().getOrDefault("DATABASE_USER","code_context"),System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password")));
        if(!"code_context_test".equals(admin.queryForObject("SELECT current_database()",String.class)))throw new IllegalStateException("shared acceptance requires the isolated code_context_test database");
        registry.add("spring.datasource.url", () -> base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA+",public");
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("DATABASE_USER", "code_context"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("DATABASE_PASSWORD", "integration_test_password"));
        registry.add("code-context.repository-root", () -> ROOT.resolve("mirrors").toString());
        registry.add("code-context.local-git-root", () -> ROOT.resolve("git-sources").toString());
        registry.add("code-context.zoekt-index-root", () -> System.getenv().getOrDefault("ZOEKT_INDEX_ROOT", ROOT.resolve("zoekt").toString()));
        registry.add("code-context.zoekt.indexer", () -> System.getenv().getOrDefault("ZOEKT_INDEXER", "zoekt-git-index"));
        registry.add("code-context.zoekt.webserver", () -> System.getenv().getOrDefault("ZOEKT_WEB_SERVER", "http://localhost:6070"));
        registry.add("spring.ai.ollama.base-url", () -> System.getenv().getOrDefault("OLLAMA_BASE_URL", "http://localhost:11434"));
        registry.add("code-context.api-keys", () -> TEAM + "=" + API_KEY + ",isolated=isolated-integration-token");
        registry.add("code-context.admin-api-keys", () -> "admin=admin-token,"+TEAM+"=integration-admin-token,isolated=isolated-integration-admin-token");
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
    public static final class SchemaHandoff extends org.springframework.test.context.support.AbstractTestExecutionListener {
        // afterTestClass callbacks run in reverse order: let DirtiesContext and all normal
        // Spring listeners finish before handing the populated catalog to the restore drill.
        @Override public int getOrder(){return Integer.MIN_VALUE;}
        @Override public void afterTestClass(org.springframework.test.context.TestContext ignored)throws Exception {
            Files.writeString(Path.of("target/postgres-acceptance-schema.txt"),SCHEMA+"\n");
        }
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
        jdbc.update("DELETE FROM contract_refresh_jobs");
        jdbc.update("DELETE FROM webhook_deliveries");
        jdbc.update("DELETE FROM github_installation_epochs");
        jdbc.update("DELETE FROM github_event_receipts");
        jdbc.update("DELETE FROM repository_lifecycle_work");
        jdbc.update("DELETE FROM index_jobs");
        jdbc.update("DELETE FROM index_generations");
        jdbc.update("DELETE FROM repository_index_leases");
        jdbc.update("DELETE FROM team_repository_access");
        jdbc.update("DELETE FROM repositories");
        if(Files.exists(ROOT))try(var paths=Files.walk(ROOT)){paths.sorted(java.util.Comparator.reverseOrder()).forEach(path->{try{Files.deleteIfExists(path);}catch(Exception e){throw new IllegalStateException(e);}});}
        Files.createDirectories(ROOT.resolve("git-sources"));
        Files.createDirectories(ROOT.resolve("maven-repository"));
        System.setProperty("MAVEN_ALLOWED_REPOSITORIES", "integration=" + ROOT.resolve("maven-repository").toUri());
    }

    @Test
    void indexesGitRepositoriesPersistsV4V5V6V7V8V9AndReturnsExactImpactThroughMcp() throws Exception {
        assertEquals(0, flyway.migrate().migrationsExecuted, "restarting against an existing migrated database must be a no-op");
        assertEquals("20", jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class));
        assertTrue(Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_extension WHERE extname='vector')", Boolean.class)));

        Path artifactRepository = ROOT.resolve("maven-repository");
        publishMoneyArtifact(artifactRepository, "1.0", "public String convert(String value) { return value; }\n    public String convert(Integer value) { return value.toString(); }");
        publishMoneyArtifact(artifactRepository, "2.0", "public String convert(java.math.BigDecimal value) { return value.toString(); }\n    public String convert(Integer value) { return value.toString(); }");

        Path providerSource = ROOT.resolve("git-sources/common-library");
        createGitRepository(providerSource, libraryFiles("1.0", "public String convert(String value) { return value; }\n    public String convert(Integer value) { return value.toString(); }"));
        onboard("common-library", providerSource, "LIBRARY");

        Path consumerASource = ROOT.resolve("git-sources/consumer-service-a");
        createGitRepository(consumerASource, consumerFiles("1.0", "ConsumerA", "String", "money.convert(\"x\")"));
        automaticallyOnboardAndAwait("consumer-service-a", consumerASource);
        Path consumerBSource = ROOT.resolve("git-sources/consumer-service-b");
        createGitRepository(consumerBSource, consumerFiles("2.0", "ConsumerB", "java.math.BigDecimal", "money.convert(java.math.BigDecimal.ONE)"));
        onboard("consumer-service-b", consumerBSource, "SERVICE");
        Path consumerCSource = ROOT.resolve("git-sources/consumer-service-c");
        createGitRepository(consumerCSource, consumerFiles("2.0", "ConsumerC", "Integer", "money.convert(Integer.valueOf(1))"));
        onboard("consumer-service-c", consumerCSource, "SERVICE");

        indexAndAwait("consumer-service-a");
        indexAndAwait("consumer-service-b");
        indexAndAwait("consumer-service-c");

        UUID provider = repositories.id("Fixture", "common-library");
        UUID consumerA = repositories.id("Fixture", "consumer-service-a");
        UUID consumerB = repositories.id("Fixture", "consumer-service-b");
        UUID consumerC = repositories.id("Fixture", "consumer-service-c");
        UUID consumerAGeneration = repositories.currentGeneration(consumerA).orElseThrow();
        UUID consumerBGeneration = repositories.currentGeneration(consumerB).orElseThrow();
        UUID consumerCGeneration = repositories.currentGeneration(consumerC).orElseThrow();
        var firstLease = repositoryIndexLeases.tryAcquire(provider, UUID.randomUUID()).orElseThrow();
        try (firstLease) {
            assertTrue(repositoryIndexLeases.tryAcquire(provider, UUID.randomUUID()).isEmpty(),
                    "a second worker must not acquire a repository lease held by another replica");
        }
        try (var replacementLease = repositoryIndexLeases.tryAcquire(provider, UUID.randomUUID()).orElseThrow()) {
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM repository_index_leases WHERE repository_id=?", Integer.class, provider),
                    "closing a lease must allow the next worker to claim the repository");
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contract_links", Integer.class),
                "consumer relationships must remain unresolved before the provider is indexed");

        // Publishing the previously missing provider must bind its exports to the already
        // published consumer imports, without requiring any consumer generation to be rebuilt.
        indexAndAwait("common-library");
        assertEquals(consumerAGeneration, repositories.currentGeneration(consumerA).orElseThrow());
        assertEquals(consumerBGeneration, repositories.currentGeneration(consumerB).orElseThrow());
        assertEquals(consumerCGeneration, repositories.currentGeneration(consumerC).orElseThrow());

        UUID providerV1Generation = repositories.currentGeneration(provider).orElseThrow();
        String providerV1Commit=repositories.currentCommit(provider).orElseThrow();
        UUID attemptJob=jdbc.queryForObject("INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state,attempt) VALUES(?,?,?,'RUNNING',2) RETURNING id",UUID.class,provider,providerV1Commit,"attempt-fencing-"+UUID.randomUUID());
        UUID unpublishedFencing=jdbc.queryForObject("INSERT INTO index_generations(repository_id,commit_sha,generation,published) SELECT ?,?,coalesce(max(generation),0)+1,false FROM index_generations WHERE repository_id=? RETURNING id",UUID.class,provider,providerV1Commit,provider);
        try {
            indexing.retryAttempt(attemptJob,1,new IllegalStateException("late failure from stale attempt"));
            assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM index_jobs WHERE id=?",String.class,attemptJob));
            assertThrows(IllegalStateException.class,()->indexing.markSucceeded(attemptJob,1,providerV1Generation));
            assertThrows(IllegalStateException.class,()->indexing.markSucceeded(attemptJob,2,unpublishedFencing),
                    "a matched attempt must not associate success with an unpublished generation at the same SHA");
            assertEquals("RUNNING",jdbc.queryForObject("SELECT state FROM index_jobs WHERE id=?",String.class,attemptJob));
            indexing.markSucceeded(attemptJob,2,providerV1Generation);
            indexing.retryAttempt(attemptJob,2,new IllegalStateException("late failure after success"));
            assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT state FROM index_jobs WHERE id=?",String.class,attemptJob));
        } finally {
            jdbc.update("DELETE FROM index_jobs WHERE id=?",attemptJob);
            jdbc.update("DELETE FROM index_generations WHERE id=?",unpublishedFencing);
        }
        var manualClient=HttpClient.newHttpClient();
        var manualResponse=manualClient.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories/Fixture/common-library/index"))
                .header("X-API-Key",API_KEY).POST(HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(202,manualResponse.statusCode(),manualResponse.body());
        UUID manualJob=UUID.fromString(json.readTree(manualResponse.body()).path("jobId").asText());
        indexing.poll();awaitSucceeded(manualJob,"manual HEAD resolution");
        var manual=jdbc.queryForMap("SELECT requested_commit_sha,commit_sha,generation_id FROM index_jobs WHERE id=?",manualJob);
        assertEquals("HEAD",manual.get("requested_commit_sha"));assertEquals(providerV1Commit,manual.get("commit_sha"));
        assertEquals(providerV1Generation,manual.get("generation_id"),"same-commit manual success must reference the actual published generation");
        var statusResponse=manualClient.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/index-jobs/"+manualJob))
                .header("X-API-Key",API_KEY).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,statusResponse.statusCode(),statusResponse.body());
        var manualStatus=json.readTree(statusResponse.body());
        assertEquals("HEAD",manualStatus.path("requested_commit_sha").asText());
        assertEquals(providerV1Commit,manualStatus.path("commit_sha").asText());
        assertEquals(providerV1Generation.toString(),manualStatus.path("generation_id").asText());
        assertFalse(manualStatus.path("generation_provenance_unverified").asBoolean(true));
        var sourceChunks=jdbc.queryForList("SELECT start_line,end_line,content FROM code_chunks WHERE generation_id=? AND file_path='src/main/java/org/acme/Money.java' AND symbol='convert(String) | convert(Integer)'",providerV1Generation);
        assertEquals(1,sourceChunks.size(),"adjacent tiny provider overloads must share one exact source chunk");
        assertEquals(3,((Number)sourceChunks.getFirst().get("start_line")).intValue());
        assertEquals(4,((Number)sourceChunks.getFirst().get("end_line")).intValue(),
                "merged overloads must retain both actual source lines");
        assertEquals(List.of("3:3","4:4"),jdbc.queryForList("SELECT start_line::text||':'||end_line::text FROM code_nodes WHERE generation_id=? AND file_path='src/main/java/org/acme/Money.java' AND node_type='METHOD' ORDER BY start_line",String.class,providerV1Generation),
                "merging retrieval chunks must not merge graph declarations or invent definition ranges");
        assertTrue(sourceChunks.getFirst().get("content").toString().contains("\nclass: org.acme.Money\n"));
        assertEquals("public String convert(String value) { return value; }\n    public String convert(Integer value) { return value.toString(); }",
                sourceChunks.getFirst().get("content").toString().split("\n---\n",2)[1]);
        var pythonChunk=jdbc.queryForList("SELECT start_line,end_line,content FROM code_chunks WHERE generation_id=? AND file_path='samples/refund_policy.py' AND symbol='def eligible(self, paid, cancelled):'",providerV1Generation);
        assertEquals(1,pythonChunk.size(),"supported Python must produce an actual method chunk rather than a file fallback");
        assertEquals(3,((Number)pythonChunk.getFirst().get("start_line")).intValue());
        assertEquals(4,((Number)pythonChunk.getFirst().get("end_line")).intValue());
        assertEquals("def eligible(self, paid, cancelled):\n        return paid > self.minimum and not cancelled",pythonChunk.getFirst().get("content").toString().split("\n---\n",2)[1]);
        var fallbackWindows=jdbc.queryForList("SELECT start_line::text||':'||end_line::text FROM code_chunks WHERE generation_id=? AND file_path='docs/fallback-notes.txt' ORDER BY start_line",String.class,providerV1Generation);
        assertTrue(fallbackWindows.size()>3,"token-heavy fallback windows must be subdivided without dropping lines");
        var fallbackChunks=jdbc.queryForList("SELECT start_line,end_line,content FROM code_chunks WHERE generation_id=? AND file_path='docs/fallback-notes.txt' ORDER BY id",providerV1Generation);
        String[] fallbackSource=libraryFiles("1.0","").get("docs/fallback-notes.txt").split("\n");
        var coveredFallbackLines=new java.util.HashSet<Integer>();
        for(var chunk:fallbackChunks) {
            int start=((Number)chunk.get("start_line")).intValue(),end=((Number)chunk.get("end_line")).intValue();
            assertTrue(end-start<60,"fallback chunks must retain the maximum sixty-line bound");
            assertEquals(String.join("\n",java.util.Arrays.copyOfRange(fallbackSource,start-1,end)),
                    chunk.get("content").toString().split("\n---\n",2)[1].stripTrailing(),"persisted fallback content must match its exact source lines");
            java.util.stream.IntStream.rangeClosed(start,end).forEach(coveredFallbackLines::add);
        }
        assertEquals(java.util.stream.IntStream.rangeClosed(1,121).boxed().collect(java.util.stream.Collectors.toSet()),coveredFallbackLines,
                "all 121 fallback source lines must remain searchable after token subdivision");
        assertEquals(List.of("1:3","4:5"),jdbc.queryForList("SELECT start_line::text||':'||end_line::text FROM code_chunks WHERE generation_id=? AND file_path='docs/guide.md' ORDER BY start_line",String.class,providerV1Generation),
                "Markdown sections must persist the actual heading boundaries");
        var configurationChunks=jdbc.queryForList("SELECT start_line,end_line,content FROM code_chunks WHERE generation_id=? AND file_path='config/runtime.yaml' ORDER BY id",providerV1Generation);
        assertTrue(configurationChunks.size()>2,"large configuration values must be split to fit the embedding token budget");
        for(var chunk:configurationChunks) {
            int start=((Number)chunk.get("start_line")).intValue(),end=((Number)chunk.get("end_line")).intValue();
            assertTrue((start>=1&&end<=2)||(start>=3&&end<=4),"configuration chunks must remain inside their top-level section: "+chunk);
        }
        String configurationSource=java.util.stream.Stream.of(1,3).map(section->configurationChunks.stream()
                .filter(c->((Number)c.get("start_line")).intValue()>=section&&((Number)c.get("end_line")).intValue()<=section+1)
                .map(c->c.get("content").toString().split("\n---\n",2)[1]).collect(java.util.stream.Collectors.joining()))
                .collect(java.util.stream.Collectors.joining("\n"));
        assertEquals(libraryFiles("1.0","").get("config/runtime.yaml").stripTrailing(),configurationSource.stripTrailing(),
                "splitting long configuration values must preserve all source characters in section order");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=? AND octet_length(content)+32>1500",Integer.class,providerV1Generation),
                "all actual Ollama embedding inputs, including headers and reserved tokens, must fit the conservative token budget");
        assertTrue(zoektGenerationHasMatches("Fixture/common-library", providerV1Generation),
                "Zoekt must expose the indexed provider v1 generation before replacement");

        String consumerACommit = repositories.currentCommit(consumerA).orElseThrow();
        String consumerBCommit = repositories.currentCommit(consumerB).orElseThrow();
        String consumerCCommit = repositories.currentCommit(consumerC).orElseThrow();
        assertMavenClasspathPersisted(consumerA, consumerAGeneration, consumerACommit);
        assertCrossRepositoryRestAndKafkaContracts(provider, consumerA, consumerB);
        assertCallPersisted(consumerA, consumerAGeneration, consumerACommit,
                "org.acme.Money#convert(java.lang.String)", "src/main/java/app/ConsumerA.java");
        assertEquals(1,jdbc.queryForObject("""
                SELECT count(*) FROM semantic_call_sites WHERE repository_id=? AND generation_id=? AND commit_sha=?
                  AND file_path='src/main/java/app/ConsumerA.java' AND declaring_type='org.acme.Money'
                  AND resolved_signature='convert(java.lang.String)' AND resolution_status='RESOLVED'
                  AND dependency_artifact='org.acme:common-library:1.0'
                  AND evidence LIKE '%declarationOrigin=JAR; binaryClass=org.acme.Money%'
                """,Integer.class,consumerA,consumerAGeneration,consumerACommit),
                "external call-site persistence must retain the actual binary declaration origin");
        assertEquals(2,jdbc.queryForObject("""
                SELECT count(*) FROM semantic_call_sites WHERE repository_id=? AND generation_id=? AND commit_sha=?
                  AND file_path='src/main/java/app/EntryPoint.java' AND resolution_status='RESOLVED'
                  AND declaring_type IN ('app.ConsumerA','app.EntryPoint') AND target_symbol_id IS NOT NULL
                  AND dependency_artifact IS NULL AND evidence LIKE '%declarationOrigin=SOURCE;%'
                """,Integer.class,consumerA,consumerAGeneration,consumerACommit),
                "repository source declarations must retain source evidence without external dependency attribution");
        assertCallPersisted(consumerA, consumerAGeneration, consumerACommit,
                "org.acme.Money#convert(java.lang.String)", "src/main/kotlin/app/ConsumerAKotlin.kt");
        assertUnresolvedCallRemainsUnresolved(consumerA, consumerAGeneration, consumerACommit);
        assertCallPersisted(consumerB, consumerBGeneration, consumerBCommit,
                "org.acme.Money#convert(java.math.BigDecimal)", "src/main/java/app/ConsumerB.java");
        assertCallPersisted(consumerC, consumerCGeneration, consumerCCommit,
                "org.acme.Money#convert(java.lang.Integer)", "src/main/java/app/ConsumerC.java");
        assertCallPersisted(consumerC, consumerCGeneration, consumerCCommit,
                "org.acme.Money#convert(java.lang.Integer)", "src/main/kotlin/app/ConsumerKotlinPeer.kt");
        assertCallPersisted(consumerC, consumerCGeneration, consumerCCommit,
                "app.ConsumerKotlinPeer#label()", "src/main/java/app/ConsumerJavaPeer.java");
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM semantic_call_sites WHERE generation_id=? AND file_path='src/main/kotlin/app/ConsumerAKotlin.kt' AND declaring_type='org.acme.Money' AND extractor_version='kotlin-compiler-jvm' AND resolution_status='RESOLVED'",Integer.class,consumerAGeneration));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM semantic_call_sites WHERE generation_id=? AND file_path='src/main/kotlin/app/ConsumerKotlinPeer.kt' AND declaring_type='org.acme.Money' AND extractor_version='kotlin-compiler-jvm' AND resolution_status='RESOLVED'",Integer.class,consumerCGeneration));
        assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM contracts WHERE generation_id=? AND contract_type='MAVEN' AND role='EXPORT' AND metadata->'kotlinJvmAbi' @> '[\"app.ConsumerAKotlin#call(Lorg/acme/Money;)Ljava/lang/String;\"]'::jsonb AND metadata->>'kotlinSourceCompatibility'='UNKNOWN')",Boolean.class,consumerAGeneration),"Consumer A Kotlin ABI export must persist with UNKNOWN source compatibility");
        assertTrue(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM contracts WHERE generation_id=? AND contract_type='MAVEN' AND role='EXPORT' AND metadata->'kotlinJvmAbi' @> '[\"app.ConsumerKotlinPeer#label()Ljava/lang/String;\"]'::jsonb AND metadata->>'kotlinSourceCompatibility'='UNKNOWN')",Boolean.class,consumerCGeneration),"compiler-derived exported Kotlin ABI and source-compatibility limitation must persist with the Maven contract");

        verifyIncrementalAdditionChangeAndDeletion(consumerCSource, consumerC);
        assertEquals("1.0", dependencyVersion(consumerA));
        assertEquals("2.0", dependencyVersion(consumerB));
        assertEquals("2.0", dependencyVersion(consumerC));
        assertSemanticCallSitePersistenceRoundTrip(consumerA, consumerAGeneration);
        var reloadedConsumerA = contractIndexes.published(consumerA).orElseThrow().index();
        var reloadedMavenImport = reloadedConsumerA.imports().stream()
                .filter(entry -> entry.type() == ContractType.MAVEN)
                .findFirst().orElseThrow();
        assertEquals("maven:org.acme:common-library", reloadedMavenImport.canonicalKey());
        assertEquals("1.0", reloadedMavenImport.dependencyVersion());
        assertTrue(reloadedMavenImport.metadata().containsKey("evidenceComplete"));

        Map<String,String> providerV2 = libraryFiles("2.0",
                "public String convert(java.math.BigDecimal value) { return value.toString(); }\n    public String convert(Integer value) { return value.toString(); }");
        providerV2.forEach((path, content) -> write(providerSource, path, content));
        try (Git providerGit = Git.open(providerSource.toFile())) { commit(providerGit, "publish common-library 2.0"); }
        indexAndAwait("common-library");

        UUID providerV2Generation = repositories.currentGeneration(provider).orElseThrow();
        assertTrue(zoektGenerationHasMatches("Fixture/common-library", providerV2Generation),
                "Zoekt must expose the indexed provider v2 generation after publication");
        assertFalse(zoektGenerationHasMatches("Fixture/common-library", providerV1Generation,false),
                "obsolete Zoekt generation must be deleted after replacement publication");
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=?", Integer.class, provider));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=? AND published", Integer.class, provider));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM maven_module_classpaths cp JOIN index_generations g ON g.id=cp.generation_id WHERE cp.repository_id=? AND cp.version='1.0' AND g.published=false",Integer.class,provider));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM maven_module_classpaths WHERE repository_id=? AND resolution_status='RESOLVED' AND version='2.0'", Integer.class, provider) > 0);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM maven_module_dependencies WHERE generation_id IN (SELECT generation_id FROM maven_module_classpaths WHERE repository_id=?) AND artifact_id='common-library' AND version IN ('1.0','2.0')", Integer.class, consumerA) > 0);

        JsonNode mcpResult = mcpImpactCall("Fixture/common-library");
        assertFalse(mcpResult.path("isError").asBoolean(false), mcpResult.toString());
        JsonNode payload = structuredToolPayload(mcpResult);
        JsonNode proposed = mcpImpactCallWithProposedChange(
                "Fixture/common-library",
                "org.acme.Money#convert(java.lang.String)",
                "org.acme.Money#convert(java.math.BigDecimal)");
        assertFalse(proposed.path("result").path("isError").asBoolean(false), proposed.toString());
        JsonNode proposedPayload = structuredToolPayload(proposed);
        assertEquals("SIGNATURE_CHANGED", proposedPayload.path("proposedChange").path("kind").asText(), proposedPayload.toString());
        assertEquals("POTENTIAL_BINARY_INCOMPATIBILITY",
                proposedPayload.path("proposedChange").path("impactClass").asText(), proposedPayload.toString());
        assertFalse(proposedPayload.path("proposedChange").path("confirmedBreakage").asBoolean(true));
        assertTrue(payload.has("semanticMethodImpacts"),payload.toString());
        assertFalse(payload.path("blocking").asBoolean(true),payload.toString());
        assertFalse(payload.path("methodChainMultiHopSupported").asBoolean(true), payload.toString());
        assertEquals(3, payload.path("contractHopBound").asInt(), payload.toString());
        assertTrue(payload.path("analysisCompletenessFlags").toString().contains("METHOD_CHAIN_MULTI_HOP_UNSUPPORTED"),
                payload.toString());
        int refreshScheduled=payload.path("consumerRefreshScheduled").asInt();
        assertTrue(refreshScheduled>0,"provider change should durably schedule relevant consumer refreshes: "+payload);
        UUID providerGeneration=repositories.currentGeneration(provider).orElseThrow();
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM contract_refresh_jobs WHERE origin_generation_id=?",Integer.class,providerGeneration)>=refreshScheduled,
                "reported refresh work must be present in the durable PostgreSQL queue");
        JsonNode impacts=payload.path("semanticMethodImpacts");
        assertEquals(2,impacts.size(),impacts.toString());
        JsonNode javaImpact=null,kotlinImpact=null;
        for(JsonNode impact:impacts) {
            if("src/main/java/app/ConsumerA.java".equals(impact.path("filePath").asText()))javaImpact=impact;
            if("src/main/kotlin/app/ConsumerAKotlin.kt".equals(impact.path("filePath").asText()))kotlinImpact=impact;
        }
        assertNotNull(javaImpact,"Java→Java impact missing: "+impacts);
        assertNotNull(kotlinImpact,"Kotlin→Java impact missing: "+impacts);
        for(JsonNode impact:List.of(javaImpact,kotlinImpact)) {
            assertEquals("fixture/common-library",impact.path("providerRepository").asText());
            assertEquals("fixture/consumer-service-a",impact.path("consumerRepository").asText());
            assertEquals("org.acme:common-library:1.0",impact.path("dependencyArtifact").asText());
            assertEquals("1.0",impact.path("dependencyVersion").asText());
            assertEquals("POTENTIAL_UPGRADE_IMPACT",impact.path("classification").asText());
            assertEquals("CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN",impact.path("currentCompatibility").asText());
            assertEquals("DIRECT",impact.path("relationship").asText());
            assertEquals("RESOLVED",impact.path("resolutionState").asText());
            assertEquals("CURRENT",impact.path("freshness").asText());
            assertTrue(impact.path("confidence").asDouble()>0.0);
            assertFalse(impact.path("evidence").asText().isBlank());
            assertTrue(impact.path("provenance").asText().contains("MAVEN_RESOLVER"));
            assertFalse(impact.path("blocking").asBoolean(true));
            assertTrue(impact.path("providerOldApiSignature").asText().contains("convert(String"));
            assertTrue(impact.path("providerNewApiSignature").asText().contains("convert(java.math.BigDecimal"));
            assertTrue(impact.path("providerCommit").asText().matches("[0-9a-f]{40}"));
            assertTrue(impact.path("consumerCommit").asText().matches("[0-9a-f]{40}"));
        }
        assertEquals("app.ConsumerA",javaImpact.path("callerClass").asText());
        assertEquals("call(org.acme.Money)",javaImpact.path("callerMethod").asText());
        assertEquals(5,javaImpact.path("startLine").asInt());
        assertEquals("PUBLISHED_V5_EDGE",javaImpact.path("analysisCompleteness").asText());
        assertTrue(javaImpact.path("provenance").asText().contains("JAVAPARSER_SYMBOL_SOLVER"));
        assertEquals("app.ConsumerAKotlin",kotlinImpact.path("callerClass").asText());
        assertEquals("PUBLISHED_KOTLIN_JVM_EDGE",kotlinImpact.path("analysisCompleteness").asText());
        assertTrue(kotlinImpact.path("provenance").asText().contains("KOTLIN_COMPILER_JVM"));
        assertTrue(kotlinImpact.path("provenance").asText().contains("KOTLIN_SOURCE_COMPATIBILITY_UNKNOWN"));
        assertFalse(impacts.toString().contains("consumer-service-b"));
        assertFalse(impacts.toString().contains("consumer-service-c"));
        assertTrue(payload.path("intraRepositoryCallerChainsSupported").asBoolean(false), payload.toString());
        JsonNode callerChains = payload.path("publishedCallerChains");
        assertEquals("INTRA_REPOSITORY_PUBLISHED_RESOLVED_CALL_GRAPH", callerChains.path("scope").asText());
        assertFalse(callerChains.path("complete").asBoolean(true));
        assertFalse(callerChains.path("truncated").asBoolean(true));
        assertFalse(callerChains.path("depthBoundReached").asBoolean(true));
        assertEquals(2, callerChains.path("chains").size(), callerChains.toString());
        JsonNode upstream = callerChains.path("chains").get(1);
        assertEquals("app.EntryPoint#request(app.ConsumerA, org.acme.Money)", upstream.path("callerSymbol").asText());
        assertEquals("app.ConsumerA#call(org.acme.Money)", upstream.path("directConsumerSymbol").asText());
        assertEquals(2, upstream.path("depth").asInt());
        assertEquals("fixture/consumer-service-a", upstream.path("consumerRepository").asText());
        assertEquals(consumerACommit, upstream.path("consumerCommit").asText());
        assertEquals(consumerAGeneration.toString(), upstream.path("generationId").asText());
        assertEquals("org.acme.Money#convert(java.lang.String)", upstream.path("providerSymbol").asText());
        assertEquals("org.acme:common-library:1.0", upstream.path("dependencyArtifact").asText());
        assertEquals("CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN", upstream.path("currentCompatibility").asText());
        assertEquals("POTENTIAL_UPGRADE_CALLER", upstream.path("classification").asText());
        assertFalse(upstream.path("confirmedBreakage").asBoolean(true));
        assertEquals(List.of("app.EntryPoint#request(app.ConsumerA, org.acme.Money)",
                        "app.EntryPoint#load(app.ConsumerA, org.acme.Money)", "app.ConsumerA#call(org.acme.Money)"),
                java.util.stream.StreamSupport.stream(upstream.path("symbols").spliterator(), false).map(JsonNode::asText).toList());
        for (JsonNode edge : upstream.path("callSites")) {
            assertEquals(consumerAGeneration.toString(), edge.path("generation").asText());
            assertEquals("src/main/java/app/EntryPoint.java", edge.path("file").asText());
            assertTrue(edge.path("startLine").asInt() > 0);
            assertTrue(edge.path("confidence").asDouble() > 0);
            assertFalse(edge.path("evidence").asText().isBlank());
        }
        var callerResolver = new com.wynk.codecontext.port.contract.graph.PublishedCallerChainResolver(jdbc);
        var rootImpact = json.readValue(javaImpact.toString(),
                com.wynk.codecontext.port.contract.graph.CrossRepoImpactResolver.SemanticMethodImpact.class);
        String snapshotKey = "fixture/consumer-service-a@" + consumerACommit;
        assertTrue(callerResolver.resolve("team-without-access", List.of(rootImpact),
                Map.of(snapshotKey, consumerAGeneration)).chains().isEmpty(), "caller graph must enforce team ACLs");
        assertTrue(callerResolver.resolve(TEAM, List.of(rootImpact),
                Map.of(snapshotKey, UUID.randomUUID())).chains().isEmpty(), "caller graph must enforce the captured generation");
        JsonNode contractCandidates=payload.path("impactCandidates");
        assertTrue(contractCandidates.toString().contains("rest:GET:/orders/{id}"),"HTTP removal must reach the authenticated MCP impact response: "+contractCandidates);
        // Impact candidates serialize RepoRef as nested owner/name (Fixture casing), not lowercase "fixture/..." keys.
        assertTrue(contractCandidates.toString().contains("\"name\":\"consumer-service-a\"")
                        && contractCandidates.toString().contains("rest:GET:/orders/{id}"),
                "REST consumer evidence must identify Consumer A: "+contractCandidates);
        assertTrue(contractCandidates.toString().contains("kafka:order-events"),"Kafka topic removal must reach the authenticated MCP impact response: "+contractCandidates);
        assertTrue(contractCandidates.toString().contains("\"name\":\"consumer-service-b\"")
                        && contractCandidates.toString().contains("kafka:order-events"),
                "Kafka consumer evidence must identify Consumer B: "+contractCandidates);
        JsonNode changes = payload.path("changes");
        assertTrue(changes.toString().contains("rest:GET:/catalog/{id}"),
                "OpenAPI nested $ref schema evolution must appear in MCP changes: " + changes);
        assertTrue(changes.toString().contains("kafka:inventory-events"),
                "Avro fields{} evolution must appear in MCP changes: " + changes);
        assertTrue(changes.toString().contains("BREAKING") || changes.toString().contains("breaking")
                        || changes.toString().contains("ENDPOINT_SIGNATURE_CHANGED")
                        || changes.toString().contains("SCHEMA"),
                "schema evolution should classify a detectable contract change: " + changes);

        for (String detail : List.of("parameter 'query:state'", "response field 'state'")) {
            var enumChanges = java.util.stream.StreamSupport.stream(changes.spliterator(), false)
                    .filter(change -> "rest:GET:/catalog/{id}".equals(change.path("identity").path("canonicalKey").asText()))
                    .filter(change -> change.path("detail").asText().contains(detail)).toList();
            assertEquals(1, enumChanges.size(), "enum evolution must reach real HTTP MCP: " + changes);
            assertEquals("BREAKING", enumChanges.getFirst().path("compatibility").asText());
            assertEquals("ENDPOINT_SIGNATURE_CHANGED", enumChanges.getFirst().path("changeType").asText());
            assertTrue(enumChanges.getFirst().path("beforeSignature").asText().contains("enum64~"));
            assertTrue(enumChanges.getFirst().path("afterSignature").asText().contains("enum64~"));
        }
        String persistedImpact = jdbc.queryForObject("SELECT result::text FROM cross_repo_impact_analyses WHERE team=? AND origin_repository_id=? ORDER BY created_at DESC LIMIT 1", String.class, TEAM, provider);
        assertNotNull(persistedImpact);
        JsonNode persistedImpactJson = json.readTree(persistedImpact);
        JsonNode persistedSemanticImpacts = persistedImpactJson.path("semanticMethodImpacts");
        assertEquals(2, persistedSemanticImpacts.size(), persistedImpact);
        assertTrue(persistedImpact.contains("src/main/java/app/ConsumerA.java"));
        assertTrue(persistedImpact.contains("src/main/kotlin/app/ConsumerAKotlin.kt"));
        assertTrue(persistedImpact.contains("PUBLISHED_KOTLIN_JVM_EDGE"));
        assertTrue(persistedImpact.contains("KOTLIN_SOURCE_COMPATIBILITY_UNKNOWN"));
        for(JsonNode persistedSemanticImpact:persistedSemanticImpacts) {
            assertEquals("fixture/consumer-service-a", persistedSemanticImpact.path("consumerRepository").asText());
            assertFalse(persistedSemanticImpact.path("blocking").asBoolean(true));
        }
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

        // Recover the same SHA after failed candidates: commit equality cannot establish publication.
        assertNull(jdbc.queryForObject("SELECT generation_id FROM index_jobs WHERE id=?",UUID.class,failedJob));
        org.mockito.Mockito.doCallRealMethod().when(zoektIndexer).index(any(RepositoryRecord.class),any(Path.class),any(UUID.class));
        indexAndAwait("common-library","successful-retry-"+UUID.randomUUID());
        var recovered=contractIndexes.published(provider).orElseThrow();
        assertEquals(failedCommit,recovered.index().commit());assertNotEquals(lastPublished,recovered.generationId());
        assertEquals(lastPublished,contractIndexes.previousSuccessful(provider,recovered.generationNumber()).orElseThrow().generationId(),
                "a later success at the same SHA must not turn failed candidates into successful baselines");
    }


    @Test
    void updatesRepositoryIdentityBranchUrlAndOwnershipAndReturnsOnlyCurrentSourceThroughHttpMcp()throws Exception {
        String path="src/main/java/lifecycle/LifecycleMarker.java";
        Path original=ROOT.resolve("git-sources/lifecycle-original"),alternative=ROOT.resolve("git-sources/lifecycle-alternative");
        createGitRepository(original,Map.of(path,lifecycleSource("alpha")));
        createGitRepository(alternative,Map.of(path,lifecycleSource("bravo"),"package.json","{\"private\":true,\"name\":\"lifecycle-fixture\"}\n",
                ".code-context.yml","team: untrusted-source-team\ncommand: never-execute-this\n"));
        String main,pilot;
        try(Git git=Git.open(alternative.toFile())) {
            main=git.getRepository().resolve("HEAD").name();git.checkout().setCreateBranch(true).setName("pilot").call();
            write(alternative,path,lifecycleSource("charlie"));git.add().addFilepattern(".").call();
            pilot=git.commit().setMessage("pilot fixture").setAuthor("Fixture","fixture@example.test").setCommitter("Fixture","fixture@example.test").call().name();
            git.checkout().setName("main").call();
        }
        automaticallyOnboardAndAwait("lifecycle-original",original);
        var alias=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories"))
                .header("X-API-Key","integration-admin-token").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("owner","fixture","name","lifecycle-original","cloneUrl",original.toUri().toString(),"defaultBranch","main","type","SERVICE")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(409,alias.statusCode(),"case aliases must not create duplicate provider identities or overlapping mirror paths");
        UUID id=repositories.id("Fixture","lifecycle-original");
        UUID before=repositories.currentGeneration(id).orElseThrow();
        var oldClient=new LifecycleMcp(API_KEY);var newClient=new LifecycleMcp("isolated-integration-token");
        assertTrue(oldClient.file("Fixture/lifecycle-original",path).path("content").asText().contains("alpha"));
        var client=HttpClient.newHttpClient();JsonNode transition;
        try(var lease=awaitLifecycleLease(id)) {
            var changed=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories/Fixture/lifecycle-original"))
                    .header("X-API-Key","integration-admin-token").header("Content-Type","application/json")
                    .method("PATCH",HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("name","lifecycle-renamed","cloneUrl",alternative.toUri().toString(),
                            "defaultBranch","pilot","team","isolated","expectedRevision",1)))).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(202,changed.statusCode(),changed.body());transition=json.readTree(changed.body());
            assertEquals(id.toString(),transition.path("repository").path("id").asText());assertEquals(2,transition.path("revision").asInt());
            assertFalse(transition.path("enabled").asBoolean());
            assertEquals(404,configuration("Fixture/lifecycle-renamed","isolated-integration-token").statusCode(),"configuration discovery must hide disabled transition generations");
            lease.assertOwned();
            var rejected=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE index_generations SET published=true WHERE id=?",before));
            Throwable root=rejected;while(root.getCause()!=null)root=root.getCause();
            assertInstanceOf(java.sql.SQLException.class,root);assertEquals("23514",((java.sql.SQLException)root).getSQLState());
            assertTrue(newClient.symbols("Fixture/lifecycle-renamed").isEmpty(),"disabled transition must hide the previous symbol graph");
        }
        UUID targetJob=UUID.fromString(transition.path("indexJob").path("id").asText());
        awaitSucceeded(targetJob,"lifecycle-renamed");
        assertEquals(id,repositories.id("Fixture","lifecycle-renamed"));assertTrue(repositories.find("Fixture","lifecycle-original").isEmpty());
        var current=newClient.file("Fixture/lifecycle-renamed",path);
        assertEquals("Fixture/lifecycle-renamed",current.path("repository").asText());
        assertEquals(pilot,current.path("commit").asText());assertTrue(current.path("content").asText().contains("charlie"));
        var symbols=newClient.symbols("Fixture/lifecycle-renamed");assertFalse(symbols.isEmpty(),"new published symbols must be visible to the new owning team");
        for(var symbol:symbols){assertEquals("Fixture/lifecycle-renamed",symbol.path("repository").asText());assertEquals(pilot,symbol.path("commit").asText());}
        assertEquals(2L,jdbc.queryForObject("SELECT repository_revision FROM index_generations WHERE id=?",Long.class,repositories.currentGeneration(id).orElseThrow()));
        assertEquals(401,configuration("Fixture/lifecycle-renamed",null).statusCode());
        assertEquals(401,configuration("Fixture/lifecycle-renamed","invalid-fixture-key").statusCode());
        assertEquals(404,configuration("Fixture/lifecycle-renamed",API_KEY).statusCode(),"the former owner must not retain discovered configuration access");
        var discoveredResponse=configuration("Fixture/lifecycle-renamed","isolated-integration-token");
        assertEquals(200,discoveredResponse.statusCode(),discoveredResponse.body());var discovered=json.readTree(discoveredResponse.body());
        assertEquals(id.toString(),discovered.path("repositoryId").asText());assertEquals(pilot,discovered.path("commit").asText());
        assertEquals(repositories.currentGeneration(id).orElseThrow().toString(),discovered.path("generationId").asText());
        assertEquals(2,discovered.path("configurationRevision").asInt());assertEquals("pilot",discovered.path("defaultBranch").asText());
        assertEquals("SERVICE",discovered.path("repositoryType").asText());
        assertEquals(List.of(".code-context.yml","package.json"),java.util.stream.StreamSupport.stream(discovered.path("discovery").path("manifests").spliterator(),false).map(item->item.path("path").asText()).toList());
        assertEquals(List.of("NODE"),json.convertValue(discovered.path("discovery").path("buildSystems"),List.class));
        assertEquals(List.of("JAVA"),json.convertValue(discovered.path("discovery").path("languages"),List.class));
        assertFalse(discovered.toString().contains("untrusted-source-team"));assertFalse(discovered.toString().contains("never-execute"));
        assertEquals("isolated",jdbc.queryForObject("SELECT team FROM repositories WHERE id=?",String.class,id));
        assertFalse(current.path("content").asText().contains("alpha"));assertFalse(Files.exists(ROOT.resolve("mirrors/Fixture/lifecycle-original")));
        assertTrue(Files.isDirectory(original.resolve(".git")));
        oldClient.rejectFile("Fixture/lifecycle-renamed",path);
        assertTrue(oldClient.symbols("Fixture/lifecycle-renamed").isEmpty());
        var denied=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories/by-id/"+id+"/lifecycle"))
                .header("X-API-Key","integration-admin-token").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(404,denied.statusCode(),"former owner must not retain lifecycle administration");

        try(var lease=awaitLifecycleLease(id)) {
            var branch=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories/Fixture/lifecycle-renamed"))
                    .header("X-API-Key","isolated-integration-admin-token").header("Content-Type","application/json")
                    .method("PATCH",HttpRequest.BodyPublishers.ofString("{\"defaultBranch\":\"main\",\"expectedRevision\":2}")).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(202,branch.statusCode(),branch.body());
            assertTrue(newClient.symbols("Fixture/lifecycle-renamed").isEmpty(),"even the same owning team must not read a disabled generation");
            var disabled=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories/Fixture/lifecycle-renamed"))
                    .header("X-API-Key","isolated-integration-admin-token").DELETE().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,disabled.statusCode(),disabled.body());
        }
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(20).toNanos();
        while(System.nanoTime()<deadline&&jdbc.queryForObject("SELECT count(*) FROM repository_lifecycle_work WHERE repository_id=? AND state<>'DONE'",Integer.class,id)>0)Thread.sleep(100);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM repository_lifecycle_work WHERE repository_id=? AND state<>'DONE'",Integer.class,id));
        assertFalse(jdbc.queryForObject("SELECT enabled OR desired_enabled FROM repositories WHERE id=?",Boolean.class,id));
        newClient.rejectFile("Fixture/lifecycle-renamed",path);
        var restored=client.send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories"))
                .header("X-API-Key","isolated-integration-admin-token").header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("owner","Fixture","name","lifecycle-renamed","cloneUrl",alternative.toUri().toString(),"defaultBranch","main","type","SERVICE")))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,restored.statusCode(),restored.body());assertEquals(id.toString(),json.readTree(restored.body()).path("id").asText());
        UUID restarted=jdbc.queryForObject("SELECT initial_job_id FROM repository_onboarding WHERE repository_id=?",UUID.class,id);
        awaitSucceeded(restarted,"lifecycle-renamed");
        assertEquals(4L,jdbc.queryForObject("SELECT configuration_revision FROM repositories WHERE id=?",Long.class,id));
        var reactivated=newClient.file("Fixture/lifecycle-renamed",path);
        assertEquals(main,reactivated.path("commit").asText());assertTrue(reactivated.path("content").asText().contains("bravo"));
        assertFalse(reactivated.path("content").asText().contains("charlie"));oldClient.rejectFile("Fixture/lifecycle-renamed",path);
        String missed;
        try(Git git=Git.open(alternative.toFile())) {
            write(alternative,path,lifecycleSource("delta"));git.add().addFilepattern(".").call();
            missed=git.commit().setMessage("push without webhook delivery").setAuthor("Fixture","fixture@example.test").setCommitter("Fixture","fixture@example.test").call().name();
        }
        jdbc.update("UPDATE repositories SET refresh_due_at=now()-interval '1 second' WHERE id=?",id);
        UUID recovery=null;deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        while(recovery==null&&System.nanoTime()<deadline){recovery=jdbc.queryForObject("SELECT last_refresh_job_id FROM repositories WHERE id=?",UUID.class,id);if(recovery==null)Thread.sleep(100);}
        assertNotNull(recovery,"the normal scheduled reconciler must recover a source change without webhook or manual index invocation");
        awaitSucceeded(recovery,"lifecycle-renamed");
        var recovered=newClient.file("Fixture/lifecycle-renamed",path);
        assertEquals(missed,recovered.path("commit").asText());assertTrue(recovered.path("content").asText().contains("delta"));
        assertEquals(4L,jdbc.queryForObject("SELECT repository_revision FROM index_generations WHERE id=?",Long.class,repositories.currentGeneration(id).orElseThrow()));
        oldClient.rejectFile("Fixture/lifecycle-renamed",path);
        UUID unchangedGeneration=repositories.currentGeneration(id).orElseThrow(),priorRecovery=recovery;
        jdbc.update("UPDATE repositories SET refresh_due_at=now()-interval '1 second' WHERE id=?",id);
        UUID unchanged=null;deadline=System.nanoTime()+java.time.Duration.ofSeconds(15).toNanos();
        while(unchanged==null&&System.nanoTime()<deadline) {
            UUID next=jdbc.queryForObject("SELECT last_refresh_job_id FROM repositories WHERE id=?",UUID.class,id);
            if(next!=null&&!next.equals(priorRecovery))unchanged=next;else Thread.sleep(100);
        }
        assertNotNull(unchanged);awaitSucceeded(unchanged,"lifecycle-renamed");
        assertEquals(unchangedGeneration,repositories.currentGeneration(id).orElseThrow(),"an unchanged HEAD check must reuse the valid published generation");
        Files.writeString(ROOT.resolve("mirrors/Fixture/lifecycle-renamed/settings.gradle"),"untracked filesystem settings must not be discovered\n");
        var finalConfiguration=configuration("Fixture/lifecycle-renamed","isolated-integration-token");assertEquals(200,finalConfiguration.statusCode(),finalConfiguration.body());
        var currentConfiguration=json.readTree(finalConfiguration.body());assertEquals(missed,currentConfiguration.path("commit").asText());
        assertEquals(4,currentConfiguration.path("configurationRevision").asInt());assertEquals("main",currentConfiguration.path("defaultBranch").asText());
        assertEquals(List.of("NODE"),json.convertValue(currentConfiguration.path("discovery").path("buildSystems"),List.class));
        // Controlled App bindings test signed HTTP access revocation, not live GitHub provisioning.
        jdbc.update("UPDATE repository_onboarding SET webhook_mode='APP',webhook_state='READY',provider_repository_id=81,provider_installation_id=17,provider_host='github.com' WHERE repository_id=?",id);
        var foreign=repositories.insert("Fixture","not-revoked",original.toUri().toString(),"isolated","main","SERVICE",ROOT.resolve("mirrors/Fixture/not-revoked").toString());
        jdbc.update("INSERT INTO repository_onboarding(repository_id,webhook_mode,webhook_state,provider_repository_id,provider_installation_id,provider_host) VALUES(?,'APP','READY',82,18,'github.com')",foreign.id());
        UUID removal=UUID.randomUUID();
        String removed=json.writeValueAsString(Map.of("action","removed","installation",Map.of("id",17),"repository_selection","selected","repositories_removed",List.of(Map.of("id",81))));
        assertEquals(401,installationEvent("installation_repositories",removal,removed,false).statusCode());
        assertTrue(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,id));
        jdbc.execute("CREATE FUNCTION reject_webhook_receipt_fixture() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled private database fixture failure'; END $$");
        jdbc.execute("CREATE TRIGGER reject_webhook_receipt_fixture BEFORE INSERT ON github_event_receipts FOR EACH ROW EXECUTE FUNCTION reject_webhook_receipt_fixture()");
        try {
            var unavailable=installationEvent("installation_repositories",removal,removed,true);
            assertEquals(503,unavailable.statusCode(),unavailable.body());assertFalse(unavailable.body().contains("controlled private database"));
            assertTrue(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,id));
            assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM github_event_receipts WHERE delivery_id=?",Integer.class,removal));
        }finally{jdbc.execute("DROP TRIGGER reject_webhook_receipt_fixture ON github_event_receipts");jdbc.execute("DROP FUNCTION reject_webhook_receipt_fixture()");}
        try(var lease=awaitLifecycleLease(id)) {
            var revoked=installationEvent("installation_repositories",removal,removed,true);
            assertEquals(202,revoked.statusCode(),revoked.body());assertEquals(1,json.readTree(revoked.body()).path("repositoriesSuspended").asInt());
            lease.assertOwned();
            var rejected=assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE index_generations SET published=true WHERE id=?",unchangedGeneration));
            Throwable cause=rejected;while(cause.getCause()!=null)cause=cause.getCause();
            assertEquals("23514",((java.sql.SQLException)cause).getSQLState());
        }
        newClient.rejectFile("Fixture/lifecycle-renamed",path);assertTrue(newClient.symbols("Fixture/lifecycle-renamed").isEmpty());
        assertEquals(404,configuration("Fixture/lifecycle-renamed","isolated-integration-token").statusCode(),"App revocation must also revoke configuration discovery");
        var repeat=installationEvent("installation_repositories",removal,removed,true);
        assertEquals(202,repeat.statusCode(),repeat.body());assertTrue(json.readTree(repeat.body()).path("duplicate").asBoolean());
        String changed=removed.replace("81","82");assertEquals(409,installationEvent("installation_repositories",removal,changed,true).statusCode());
        assertEquals(202,installationEvent("installation",UUID.randomUUID(),"{\"action\":\"unsuspend\",\"installation\":{\"id\":17}}",true).statusCode());
        assertFalse(jdbc.queryForObject("SELECT enabled OR desired_enabled FROM repositories WHERE id=?",Boolean.class,id),"restored provider access must not automatically restore platform authorization");
        assertTrue(jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,foreign.id()),"a different installation must remain untouched");
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM team_repository_access WHERE repository_id=?",Integer.class,id));
    }
    private HttpResponse<String> configuration(String repository,String key)throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/api/repositories/"+repository+"/configuration"));
        if(key!=null)request.header("X-API-Key",key);
        return HttpClient.newHttpClient().send(request.GET().build(),HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> installationEvent(String event,UUID delivery,String payload,boolean valid)throws Exception {
        byte[] bytes=payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/webhooks/github"))
                .header("Content-Type","application/json").header("X-GitHub-Event",event).header("X-GitHub-Delivery",delivery.toString())
                .header("X-Hub-Signature-256",valid?webhookSignature(bytes):"sha256="+"0".repeat(64))
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static String lifecycleSource(String version){return "package lifecycle;\npublic class LifecycleMarker {\n public String version(){ return \""+version+"\"; }\n}\n";}
    private RepositoryIndexLeaseManager.Lease awaitLifecycleLease(UUID id)throws Exception {
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(System.nanoTime()<deadline){var lease=repositoryIndexLeases.tryAcquire(id,UUID.randomUUID());if(lease.isPresent())return lease.get();Thread.sleep(100);}
        throw new AssertionError("prior indexing publication did not release its repository lease");
    }
    private final class LifecycleMcp {
        final HttpClient client=HttpClient.newHttpClient();final URI endpoint=URI.create("http://localhost:"+port+"/mcp");final String key,session;int sequence=10;
        LifecycleMcp(String key)throws Exception {
            this.key=key;
            post(client,endpoint,null,Map.of("jsonrpc","2.0","id",1,"method","initialize","params",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","lifecycle-it","version","1"))),key);
            session=lastResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
            post(client,endpoint,session,Map.of("jsonrpc","2.0","method","notifications/initialized"),key);
        }
        JsonNode tool(String name,Map<String,Object> arguments)throws Exception{return post(client,endpoint,session,Map.of("jsonrpc","2.0","id",sequence++,"method","tools/call","params",Map.of("name",name,"arguments",arguments)),key);}
        JsonNode file(String repository,String path)throws Exception{return structuredToolPayload(tool("read_file",Map.of("repository",repository,"path",path)));}
        JsonNode symbols(String repository)throws Exception{return structuredToolPayload(tool("find_symbol",Map.of("repository",repository,"symbol","LifecycleMarker")));}
        void rejectFile(String repository,String path)throws Exception {
            var rejected=tool("read_file",Map.of("repository",repository,"path",path));
            assertTrue(rejected.path("result").path("isError").asBoolean()||rejected.path("error").isObject(),"revoked/disabled repository must reject HTTP MCP read_file");
            assertFalse(rejected.toString().contains("charlie"));
        }
    }

    @Test
    void demotesAmbiguousRestProvidersAndResolvesWhenOneProviderRemains() throws Exception {
        Path providerA = ROOT.resolve("git-sources/orders-api-a");
        Path providerB = ROOT.resolve("git-sources/orders-api-b");
        Path consumer = ROOT.resolve("git-sources/orders-client");
        createGitRepository(providerA, Map.of(
                "pom.xml", pom("1.0", null).replace("fixture", "org.acme.a"),
                "src/main/java/org/acme/a/Orders.java",
                "package org.acme.a; class Orders { @GetMapping(\"/orders/{id}\") Object find(String id) { return null; } }\n"));
        createGitRepository(providerB, Map.of(
                "pom.xml", pom("1.0", null).replace("fixture", "org.acme.b"),
                "src/main/java/org/acme/b/Orders.java",
                "package org.acme.b; class Orders { @GetMapping(\"/orders/{id}\") Object find(String id) { return null; } }\n"));
        createGitRepository(consumer, Map.of(
                "pom.xml", pom("1.0", null),
                "src/main/java/app/OrdersClient.java",
                "package app; class OrdersClient { void load() { webClient.get().uri(\"/orders/{id}\"); } }\n"));
        onboard("orders-api-a", providerA, "SERVICE");
        onboard("orders-api-b", providerB, "SERVICE");
        onboard("orders-client", consumer, "SERVICE");
        indexAndAwait("orders-api-a");
        indexAndAwait("orders-api-b");
        indexAndAwait("orders-client");

        UUID consumerGeneration = repositories.currentGeneration(repositories.id("Fixture", "orders-client")).orElseThrow();
        contractRefresher.refreshGeneration(consumerGeneration);

        Integer ambiguousLinks = jdbc.queryForObject("""
                SELECT count(*) FROM contract_links l
                JOIN contracts c ON c.id=l.consumer_contract
                JOIN index_generations cg ON cg.id=c.generation_id AND cg.published
                JOIN repositories cr ON cr.id=cg.repository_id AND cr.name='orders-client'
                WHERE c.canonical_key='rest:GET:/orders/{id}'
                  AND l.evidence::text LIKE '%AMBIGUOUS%'
                """, Integer.class);
        assertTrue(ambiguousLinks != null && ambiguousLinks >= 1,
                "two published REST providers for the same consumer import must demote to AMBIGUOUS");

        repositories.disable(repositories.id("Fixture", "orders-api-b"));
        contractRefresher.pruneSupersededRelationships();
        contractRefresher.refreshGeneration(consumerGeneration);

        Integer stillAmbiguous = jdbc.queryForObject("""
                SELECT count(*) FROM contract_links l
                JOIN contracts c ON c.id=l.consumer_contract
                JOIN index_generations cg ON cg.id=c.generation_id AND cg.published
                JOIN repositories cr ON cr.id=cg.repository_id AND cr.name='orders-client'
                WHERE c.canonical_key='rest:GET:/orders/{id}'
                  AND l.evidence::text LIKE '%AMBIGUOUS%'
                """, Integer.class);
        Integer remainingLinks = jdbc.queryForObject("""
                SELECT count(*) FROM contract_links l
                JOIN contracts c ON c.id=l.consumer_contract
                JOIN contracts p ON p.id=l.provider_contract
                JOIN index_generations cg ON cg.id=c.generation_id AND cg.published
                JOIN index_generations pg ON pg.id=p.generation_id AND pg.published
                JOIN repositories cr ON cr.id=cg.repository_id AND cr.name='orders-client'
                JOIN repositories pr ON pr.id=pg.repository_id AND pr.enabled AND pr.name='orders-api-a'
                WHERE c.canonical_key='rest:GET:/orders/{id}'
                """, Integer.class);
        assertEquals(0, stillAmbiguous, "disabling the duplicate provider must clear AMBIGUOUS markers");
        assertTrue(remainingLinks != null && remainingLinks >= 1,
                "single remaining enabled provider must retain an exact REST link after prune+refresh");

        // Durable webhook replay: same delivery ID must enqueue once against PostgreSQL.
        String sha;
        try (Git source = Git.open(providerA.toFile())) { sha = source.getRepository().resolve("HEAD").name(); }
        byte[] body = ("{\"after\":\"" + sha + "\",\"repository\":{\"full_name\":\"Fixture/orders-api-a\"},"
                + "\"head_commit\":{\"timestamp\":\"" + java.time.Instant.now().toString() + "\"}}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String signature = webhookSignature(body);
        UUID delivery = UUID.fromString("adf3f391-cfa6-4f27-8c4c-467f1b865ab9");
        var client = HttpClient.newHttpClient();
        URI webhook = URI.create("http://localhost:" + port + "/webhooks/github/Fixture/orders-api-a");
        HttpResponse<String> first = client.send(HttpRequest.newBuilder(webhook)
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", signature)
                .header("X-GitHub-Delivery", delivery.toString())
                .header("X-GitHub-Event", "push")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> replay = client.send(HttpRequest.newBuilder(webhook)
                .header("Content-Type", "application/json")
                .header("X-Hub-Signature-256", signature)
                .header("X-GitHub-Delivery", delivery.toString())
                .header("X-GitHub-Event", "push")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, first.statusCode(), first.body());
        assertEquals(202, replay.statusCode(), replay.body());
        assertTrue(first.body().contains("\"accepted\":true") || first.body().contains("\"accepted\": true"), first.body());
        assertTrue(replay.body().contains("\"duplicate\":true") || replay.body().contains("\"duplicate\": true"), replay.body());
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM webhook_deliveries WHERE delivery_id=?", Integer.class, delivery));
    }

    private static String webhookSignature(byte[] body) throws Exception {
        String secret = System.getenv().getOrDefault("WEBHOOK_SECRET", "local-webhook-secret-change-me");
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    void indexesKotlinProviderPersistsAbiAndReturnsExactCrossLanguageImpactThroughMcp() throws Exception {
        assertEquals(0, flyway.migrate().migrationsExecuted, "restarting against an existing migrated database must be a no-op");

        Path artifactRepository = ROOT.resolve("maven-repository");
        publishKotlinLibraryArtifact(artifactRepository, "1.0", kotlinLibrarySourceV1());
        publishKotlinLibraryArtifact(artifactRepository, "2.0", kotlinLibrarySourceV2());

        Path providerSource = ROOT.resolve("git-sources/kotlin-common-library");
        createGitRepository(providerSource, kotlinLibraryFiles("1.0", kotlinLibrarySourceV1()));
        onboard("kotlin-common-library", providerSource, "LIBRARY");

        Path kotlinPinnedSource = ROOT.resolve("git-sources/kotlin-consumer-k");
        createGitRepository(kotlinPinnedSource, kotlinConsumerFiles("1.0", true, "KotlinPinned",
                "fun call(amount: org.acme.kotlin.Amount)=amount.convert(\"x\")"));
        onboard("kotlin-consumer-k", kotlinPinnedSource, "SERVICE");

        Path javaPinnedSource = ROOT.resolve("git-sources/kotlin-consumer-j");
        createGitRepository(javaPinnedSource, kotlinConsumerFiles("1.0", false, "JavaPinned",
                "return amount.convert(\"x\") + org.acme.kotlin.AmountKt.entryPoint() + org.acme.kotlin.AmountJavaPeer.peer();"));
        onboard("kotlin-consumer-j", javaPinnedSource, "SERVICE");

        Path kotlinUpgradedSource = ROOT.resolve("git-sources/kotlin-consumer-ok");
        createGitRepository(kotlinUpgradedSource, kotlinConsumerFiles("2.0", true, "KotlinUpgraded",
                "fun call(amount: org.acme.kotlin.Amount)=amount.convert(1)"));
        onboard("kotlin-consumer-ok", kotlinUpgradedSource, "SERVICE");

        indexAndAwait("kotlin-consumer-k");
        indexAndAwait("kotlin-consumer-j");
        indexAndAwait("kotlin-consumer-ok");
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM contract_links", Integer.class),
                "consumer relationships must remain unresolved before the Kotlin provider is indexed");
        indexAndAwait("kotlin-common-library");

        UUID provider = repositories.id("Fixture", "kotlin-common-library");
        UUID kotlinPinned = repositories.id("Fixture", "kotlin-consumer-k");
        UUID javaPinned = repositories.id("Fixture", "kotlin-consumer-j");
        UUID kotlinUpgraded = repositories.id("Fixture", "kotlin-consumer-ok");
        UUID providerV1Generation = repositories.currentGeneration(provider).orElseThrow();
        UUID kotlinPinnedGeneration = repositories.currentGeneration(kotlinPinned).orElseThrow();
        UUID javaPinnedGeneration = repositories.currentGeneration(javaPinned).orElseThrow();
        UUID kotlinUpgradedGeneration = repositories.currentGeneration(kotlinUpgraded).orElseThrow();
        String kotlinPinnedCommit = repositories.currentCommit(kotlinPinned).orElseThrow();
        String javaPinnedCommit = repositories.currentCommit(javaPinned).orElseThrow();
        String kotlinUpgradedCommit = repositories.currentCommit(kotlinUpgraded).orElseThrow();

        assertTrue(jdbc.queryForObject("""
SELECT EXISTS(SELECT 1 FROM contracts WHERE generation_id=? AND contract_type='MAVEN' AND role='EXPORT'
 AND canonical_key='maven:org.acme:kotlin-common-library'
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.Amount#convert(Ljava/lang/String;)Ljava/lang/String;"]'::jsonb
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.Amount#convert(I)Ljava/lang/String;"]'::jsonb
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.Amount#<init>(Ljava/lang/String;)V"]'::jsonb
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.Amount#getLabel()Ljava/lang/String;"]'::jsonb
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.AmountKt#decorate(Ljava/lang/String;)Ljava/lang/String;"]'::jsonb
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.AmountKt#entryPoint()Ljava/lang/String;"]'::jsonb
 AND metadata->>'kotlinSourceCompatibility'='UNKNOWN'
 AND (metadata->>'kotlinMetadataComplete')::boolean=true)
""", Boolean.class, providerV1Generation),
                "Kotlin provider Maven export must persist exported ABI for methods, constructors, accessors, extensions and top-level functions");

        assertCallPersisted(kotlinPinned, kotlinPinnedGeneration, kotlinPinnedCommit,
                "org.acme.kotlin.Amount#convert(java.lang.String)", "src/main/kotlin/app/KotlinPinned.kt");
        assertCallPersisted(javaPinned, javaPinnedGeneration, javaPinnedCommit,
                "org.acme.kotlin.Amount#convert(java.lang.String)", "src/main/java/app/JavaPinned.java");
        assertCallPersisted(javaPinned, javaPinnedGeneration, javaPinnedCommit,
                "org.acme.kotlin.AmountKt#entryPoint()", "src/main/java/app/JavaPinned.java");
        assertCallPersisted(javaPinned, javaPinnedGeneration, javaPinnedCommit,
                "org.acme.kotlin.AmountJavaPeer#peer()", "src/main/java/app/JavaPinned.java");
        assertCallPersisted(kotlinUpgraded, kotlinUpgradedGeneration, kotlinUpgradedCommit,
                "org.acme.kotlin.Amount#convert(int)", "src/main/kotlin/app/KotlinUpgraded.kt");
        assertEquals(1, jdbc.queryForObject("""
SELECT count(*) FROM semantic_call_sites WHERE generation_id=? AND file_path='src/main/kotlin/app/KotlinPinned.kt'
 AND declaring_type='org.acme.kotlin.Amount' AND extractor_version='kotlin-compiler-jvm' AND resolution_status='RESOLVED'
 AND dependency_artifact='org.acme:kotlin-common-library:1.0' AND dependency_version='1.0'
""", Integer.class, kotlinPinnedGeneration));
        assertEquals("1.0", dependencyVersion(kotlinPinned));
        assertEquals("1.0", dependencyVersion(javaPinned));
        assertEquals("2.0", dependencyVersion(kotlinUpgraded));
        assertTrue(jdbc.queryForObject("""
SELECT count(*) FROM maven_module_dependencies d JOIN maven_module_classpaths cp
 ON cp.generation_id=d.generation_id AND cp.module_path=d.module_path
WHERE cp.repository_id=? AND d.group_id='org.acme' AND d.artifact_id='kotlin-common-library'
 AND d.version='1.0' AND d.scope='compile' AND d.artifact_path IS NOT NULL AND length(d.artifact_sha256)=64
""", Integer.class, kotlinPinned) > 0);

        write(providerSource, "pom.xml", kotlinLibraryPom("2.0"));
        write(providerSource, "src/main/kotlin/org/acme/kotlin/Amount.kt", kotlinLibrarySourceV2());
        write(providerSource, "src/main/java/org/acme/kotlin/AmountJavaPeer.java", amountJavaPeerSource());
        try (Git providerGit = Git.open(providerSource.toFile())) { commit(providerGit, "publish kotlin-common-library 2.0"); }
        indexAndAwait("kotlin-common-library");

        UUID providerV2Generation = repositories.currentGeneration(provider).orElseThrow();
        assertNotEquals(providerV1Generation, providerV2Generation);
        assertTrue(jdbc.queryForObject("""
SELECT EXISTS(SELECT 1 FROM contracts WHERE generation_id=? AND contract_type='MAVEN' AND role='EXPORT'
 AND canonical_key='maven:org.acme:kotlin-common-library'
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.Amount#convert(Ljava/math/BigDecimal;)Ljava/lang/String;"]'::jsonb
 AND metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.Amount#convert(I)Ljava/lang/String;"]'::jsonb
 AND NOT metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.Amount#convert(Ljava/lang/String;)Ljava/lang/String;"]'::jsonb
 AND NOT metadata->'kotlinJvmAbi' @> '["org.acme.kotlin.AmountKt#entryPoint()Ljava/lang/String;"]'::jsonb
 AND metadata->>'kotlinSourceCompatibility'='UNKNOWN')
""", Boolean.class, providerV2Generation),
                "head generation must record binary removals without claiming Kotlin source compatibility");

        JsonNode mcpResult = mcpImpactAnalysisOnly("Fixture/kotlin-common-library");
        assertFalse(mcpResult.path("isError").asBoolean(false), mcpResult.toString());
        JsonNode payload = structuredToolPayload(mcpResult);
        assertTrue(payload.has("semanticMethodImpacts"), payload.toString());
        assertFalse(payload.path("blocking").asBoolean(true), payload.toString());
        JsonNode impacts = payload.path("semanticMethodImpacts");
        // convert(String) removal hits Kotlin+Java pinned callers; entryPoint() removal hits the Java caller.
        assertEquals(3, impacts.size(), impacts.toString());
        JsonNode kotlinConvertImpact = null, javaConvertImpact = null, javaEntryImpact = null;
        for (JsonNode impact : impacts) {
            String file = impact.path("filePath").asText();
            String oldSig = impact.path("oldSignature").asText("");
            String invocation = impact.path("invocation").asText("");
            if ("src/main/kotlin/app/KotlinPinned.kt".equals(file)) kotlinConvertImpact = impact;
            if ("src/main/java/app/JavaPinned.java".equals(file)
                    && (oldSig.contains("convert(java.lang.String)") || invocation.contains("convert")))
                javaConvertImpact = impact;
            if ("src/main/java/app/JavaPinned.java".equals(file)
                    && (oldSig.contains("entryPoint") || invocation.contains("entryPoint")))
                javaEntryImpact = impact;
        }
        assertNotNull(kotlinConvertImpact, "Kotlin→Kotlin convert impact missing: " + impacts);
        assertNotNull(javaConvertImpact, "Java→Kotlin convert impact missing: " + impacts);
        assertNotNull(javaEntryImpact, "Java→Kotlin top-level entryPoint impact missing: " + impacts);
        for (JsonNode impact : List.of(kotlinConvertImpact, javaConvertImpact, javaEntryImpact)) {
            assertEquals("fixture/kotlin-common-library", impact.path("providerRepository").asText());
            assertEquals("org.acme:kotlin-common-library:1.0", impact.path("dependencyArtifact").asText());
            assertEquals("1.0", impact.path("dependencyVersion").asText());
            assertEquals("POTENTIAL_UPGRADE_IMPACT", impact.path("classification").asText());
            assertEquals("CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN", impact.path("currentCompatibility").asText());
            assertEquals("DIRECT", impact.path("relationship").asText());
            assertEquals("RESOLVED", impact.path("resolutionState").asText());
            assertEquals("CURRENT", impact.path("freshness").asText());
            assertFalse(impact.path("blocking").asBoolean(true));
            assertTrue(impact.path("providerOldApiSignature").asText().contains("KOTLIN_BINARY"));
            assertTrue(impact.path("providerNewApiSignature").asText().contains("KOTLIN_BINARY"));
            assertTrue(impact.path("provenance").asText().contains("KOTLIN_SOURCE_COMPATIBILITY_UNKNOWN"));
            assertTrue(impact.path("providerCommit").asText().matches("[0-9a-f]{40}"));
            assertTrue(impact.path("consumerCommit").asText().matches("[0-9a-f]{40}"));
        }
        assertEquals("fixture/kotlin-consumer-k", kotlinConvertImpact.path("consumerRepository").asText());
        assertEquals("PUBLISHED_KOTLIN_JVM_EDGE", kotlinConvertImpact.path("analysisCompleteness").asText());
        assertTrue(kotlinConvertImpact.path("provenance").asText().contains("KOTLIN_COMPILER_JVM"));
        assertEquals("fixture/kotlin-consumer-j", javaConvertImpact.path("consumerRepository").asText());
        assertTrue(javaConvertImpact.path("provenance").asText().contains("JAVAPARSER_SYMBOL_SOLVER"));
        assertTrue(javaConvertImpact.path("provenance").asText().contains("KOTLIN_ABI_BINARY")
                        || javaConvertImpact.path("analysisCompleteness").asText().contains("KOTLIN"),
                javaConvertImpact.toString());
        assertFalse(impacts.toString().contains("kotlin-consumer-ok"));
        // Exact Java-fixture identity — substring "common-library" also matches kotlin-common-library.
        assertFalse(impacts.toString().contains("fixture/common-library"));
        assertFalse(impacts.toString().contains("org.acme:common-library"));

        String persistedImpact = jdbc.queryForObject(
                "SELECT result::text FROM cross_repo_impact_analyses WHERE team=? AND origin_repository_id=? ORDER BY created_at DESC LIMIT 1",
                String.class, TEAM, provider);
        assertNotNull(persistedImpact);
        JsonNode persisted = json.readTree(persistedImpact);
        JsonNode persistedImpacts = persisted.path("semanticMethodImpacts");
        assertEquals(3, persistedImpacts.size(), persistedImpact);
        assertTrue(persistedImpacts.toString().contains("src/main/kotlin/app/KotlinPinned.kt"));
        assertTrue(persistedImpacts.toString().contains("src/main/java/app/JavaPinned.java"));
        assertTrue(persistedImpact.contains("KOTLIN_SOURCE_COMPATIBILITY_UNKNOWN"));
        // Upgraded consumers may appear in candidate/graph metadata; semantic impacts must exclude them.
        assertFalse(persistedImpacts.toString().contains("kotlin-consumer-ok"), persistedImpacts.toString());
    }

    private void assertCallPersisted(UUID repository, UUID generation, String commit, String targetKey, String path) {
        Integer rows = jdbc.queryForObject("""
SELECT count(*) FROM semantic_call_sites site
JOIN semantic_call_edges edge ON edge.call_site_id=site.id
 AND edge.repository_id=site.repository_id AND edge.generation_id=site.generation_id
JOIN semantic_symbols target ON target.id=edge.target_symbol_id
 AND target.repository_id=site.repository_id AND target.generation_id=site.generation_id
 AND target.commit_sha=site.commit_sha
JOIN index_generations g ON g.id=site.generation_id AND g.published
WHERE site.repository_id=? AND site.generation_id=? AND site.commit_sha=?
AND site.file_path=? AND site.resolution_status='RESOLVED'
AND target.symbol_key=? AND edge.resolution_status='RESOLVED'
""", Integer.class, repository, generation, commit, path, targetKey);
        assertTrue(rows != null && rows > 0, () -> "missing persisted V4/V5 resolved target " + targetKey + " in " + path
                + "; persisted call-sites=" + callSiteDiagnostics(repository, path)
                + "; Maven classpath=" + mavenClasspathDiagnostics(repository));
        if (targetKey.endsWith("java.lang.String)") && path.endsWith("ConsumerA.java")) {
            // Java fixture places money.convert("x") on line 5; Kotlin callers keep their own source lines.
            assertEquals(5, jdbc.queryForObject("SELECT start_line FROM semantic_call_sites WHERE repository_id=? AND file_path=? AND declaring_type='org.acme.Money' AND resolved_signature='convert(java.lang.String)' ORDER BY id LIMIT 1", Integer.class, repository, path));
        }
        if (path.endsWith("ConsumerAKotlin.kt")) {
            assertEquals(2, jdbc.queryForObject("SELECT start_line FROM semantic_call_sites WHERE repository_id=? AND file_path=? AND declaring_type='org.acme.Money' AND resolved_signature='convert(java.lang.String)' ORDER BY id LIMIT 1", Integer.class, repository, path));
        }
        Integer caller = jdbc.queryForObject("""
SELECT count(*) FROM semantic_call_sites site JOIN semantic_symbols caller ON caller.id=site.caller_symbol_id
 AND caller.repository_id=site.repository_id AND caller.generation_id=site.generation_id
 AND caller.commit_sha=site.commit_sha
WHERE site.repository_id=? AND site.generation_id=? AND site.commit_sha=?
AND site.file_path=? AND caller.symbol_key LIKE 'app.%#call(%'
""", Integer.class, repository, generation, commit, path);
        assertTrue(caller != null && caller > 0, "missing persisted canonical caller method for " + path);
    }

    private String callSiteDiagnostics(UUID repository, String path) {
        return jdbc.queryForList("""
SELECT site.generation_id,site.commit_sha,site.resolution_status,site.invocation,site.start_line,
       site.declaring_type,site.resolved_signature,site.parameter_types::text AS parameter_types,
       site.caller_symbol_id,caller.symbol_key AS caller_key,edge.resolution_status AS edge_status,
       edge.target_symbol_id,target.symbol_key AS target_key,g.published
FROM semantic_call_sites site
JOIN index_generations g ON g.id=site.generation_id
LEFT JOIN semantic_call_edges edge ON edge.call_site_id=site.id
LEFT JOIN semantic_symbols caller ON caller.id=site.caller_symbol_id
LEFT JOIN semantic_symbols target ON target.id=edge.target_symbol_id
WHERE site.repository_id=? AND site.file_path=?
ORDER BY g.generation DESC,site.start_line,site.id
""", repository, path).toString();
    }

    private String mavenClasspathDiagnostics(UUID repository) {
        return jdbc.queryForList("""
SELECT cp.generation_id,cp.commit_sha,cp.module_path,cp.resolution_status,cp.reason,cp.classpath_fingerprint,
       d.group_id,d.artifact_id,d.version,d.scope,d.artifact_path
FROM maven_module_classpaths cp
LEFT JOIN maven_module_dependencies d ON d.generation_id=cp.generation_id AND d.module_path=cp.module_path
WHERE cp.repository_id=?
ORDER BY cp.generation_id,cp.module_path,d.group_id,d.artifact_id,d.version
""", repository).toString();
    }

    private void assertMavenClasspathPersisted(UUID repository, UUID generation, String commit) {
        Integer modules = jdbc.queryForObject("""
SELECT count(*) FROM maven_module_classpaths
WHERE repository_id=? AND generation_id=? AND commit_sha=? AND module_path=''
  AND group_id='fixture' AND artifact_id='consumer' AND version='1.0'
  AND resolution_status='RESOLVED' AND classpath_fingerprint IS NOT NULL
""", Integer.class, repository, generation, commit);
        assertEquals(1, modules, "consumer POM classpath was not persisted for its published generation");
        Integer dependencies = jdbc.queryForObject("""
SELECT count(*) FROM maven_module_dependencies
WHERE generation_id=? AND module_path='' AND group_id='org.acme'
  AND artifact_id='common-library' AND version='1.0' AND scope='compile'
  AND artifact_path IS NOT NULL AND length(artifact_sha256)=64
""", Integer.class, generation);
        assertEquals(1, dependencies, "common-library dependency was not persisted with its resolved artifact");
    }

    private void assertUnresolvedCallRemainsUnresolved(UUID repository, UUID generation, String commit) {
        Boolean unresolved = jdbc.queryForObject("""
SELECT EXISTS(
 SELECT 1 FROM semantic_call_sites site
 JOIN semantic_call_edges edge ON edge.call_site_id=site.id
 WHERE site.repository_id=? AND site.generation_id=? AND site.commit_sha=?
   AND site.file_path='src/main/java/app/ConsumerA.java'
   AND site.invocation='money.missing(\"x\")'
   AND site.resolution_status='UNRESOLVED' AND site.target_symbol_id IS NULL
   AND edge.resolution_status='UNRESOLVED' AND edge.target_symbol_id IS NULL
)
""", Boolean.class, repository, generation, commit);
        assertTrue(Boolean.TRUE.equals(unresolved), "a nonexistent Money method must remain unresolved without a target symbol");
    }

    private String dependencyVersion(UUID repository) {
        return jdbc.queryForObject("SELECT dependency_version FROM semantic_call_sites WHERE repository_id=? AND resolution_status='RESOLVED' AND dependency_version IS NOT NULL ORDER BY id LIMIT 1", String.class, repository);
    }

    private void assertSemanticCallSitePersistenceRoundTrip(UUID repository, UUID generation) throws Exception {
        Map<String,Object> existing = jdbc.queryForMap("""
SELECT caller_symbol_id,target_symbol_id,commit_sha FROM semantic_call_sites
WHERE repository_id=? AND generation_id=? AND file_path='src/main/java/app/ConsumerA.java'
 AND resolution_status='RESOLVED' AND caller_symbol_id IS NOT NULL AND target_symbol_id IS NOT NULL
ORDER BY id LIMIT 1
""", repository, generation);
        Long callerId = ((Number) existing.get("caller_symbol_id")).longValue();
        Long targetId = ((Number) existing.get("target_symbol_id")).longValue();
        String commit = (String) existing.get("commit_sha");
        var resolved = new CallSite("money.convert(\"round-trip\")", 7, 7, "app.ConsumerA",
                "call(org.acme.Money)", "RESOLVED", "org.acme.Money", "convert(java.lang.String)",
                List.of("java.lang.String"), "java.lang.String", "JAVAPARSER_SYMBOL_SOLVER");
        Long resolvedId = indexing.persistSemanticCallSite(repository, generation, commit,
                "src/main/java/app/ConsumerA.java", resolved, "round-trip evidence", callerId, targetId,
                "org.acme:common-library:1.0", "1.0");
        try {
            Map<String,Object> persisted = jdbc.queryForMap("""
SELECT repository_id,generation_id,commit_sha,file_path,caller_class,caller_method,start_line,end_line,
 invocation,declaring_type,resolved_signature,parameter_types::text AS parameter_types,return_type,
 resolution_status,evidence,confidence,extractor_version,caller_symbol_id,target_symbol_id,
 dependency_artifact,dependency_version
FROM semantic_call_sites WHERE id=?
""", resolvedId);
            assertEquals(repository, persisted.get("repository_id"));
            assertEquals(generation, persisted.get("generation_id"));
            assertEquals(commit, persisted.get("commit_sha"));
            assertEquals("src/main/java/app/ConsumerA.java", persisted.get("file_path"));
            assertEquals("app.ConsumerA", persisted.get("caller_class"));
            assertEquals("call(org.acme.Money)", persisted.get("caller_method"));
            assertEquals(7, ((Number) persisted.get("start_line")).intValue());
            assertEquals(7, ((Number) persisted.get("end_line")).intValue());
            assertEquals(resolved.expression(), persisted.get("invocation"));
            assertEquals("org.acme.Money", persisted.get("declaring_type"));
            assertEquals("convert(java.lang.String)", persisted.get("resolved_signature"));
            assertEquals(List.of("java.lang.String"), json.readValue((String) persisted.get("parameter_types"),
                    new tools.jackson.core.type.TypeReference<List<String>>() {}));
            assertEquals("java.lang.String", persisted.get("return_type"));
            assertEquals("RESOLVED", persisted.get("resolution_status"));
            assertEquals("round-trip evidence", persisted.get("evidence"));
            assertEquals(0.85, ((Number) persisted.get("confidence")).doubleValue(), 0.00001);
            assertEquals("javaparser-symbol-solver-3.27.1", persisted.get("extractor_version"));
            assertEquals(callerId, ((Number) persisted.get("caller_symbol_id")).longValue());
            assertEquals(targetId, ((Number) persisted.get("target_symbol_id")).longValue());
            assertEquals("org.acme:common-library:1.0", persisted.get("dependency_artifact"));
            assertEquals("1.0", persisted.get("dependency_version"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM semantic_call_edges WHERE call_site_id=?", Integer.class, resolvedId));
        } finally {
            jdbc.update("DELETE FROM semantic_call_sites WHERE id=?", resolvedId);
        }

        var unresolved = new CallSite("money.convert(unknown)", 9, 9, null, null, "UNRESOLVED",
                null, null, List.of(), null, "NO_TYPE_RESOLUTION");
        Long unresolvedId = indexing.persistSemanticCallSite(repository, generation, commit,
                "src/main/java/app/ConsumerA.java", unresolved, "nullable round-trip evidence", null, null, null, null);
        try {
            Map<String,Object> persisted = jdbc.queryForMap("""
SELECT caller_class,caller_method,declaring_type,resolved_signature,parameter_types::text AS parameter_types,
 return_type,resolution_status,confidence,caller_symbol_id,target_symbol_id,dependency_artifact,dependency_version
FROM semantic_call_sites WHERE id=?
""", unresolvedId);
            assertNull(persisted.get("caller_class"));
            assertNull(persisted.get("caller_method"));
            assertNull(persisted.get("declaring_type"));
            assertNull(persisted.get("resolved_signature"));
            assertEquals(List.of(), json.readValue((String) persisted.get("parameter_types"),
                    new tools.jackson.core.type.TypeReference<List<String>>() {}));
            assertNull(persisted.get("return_type"));
            assertEquals("UNRESOLVED", persisted.get("resolution_status"));
            assertEquals(0.0, ((Number) persisted.get("confidence")).doubleValue(), 0.00001);
            assertNull(persisted.get("caller_symbol_id"));
            assertNull(persisted.get("target_symbol_id"));
            assertNull(persisted.get("dependency_artifact"));
            assertNull(persisted.get("dependency_version"));
            assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM semantic_call_edges WHERE call_site_id=?", Integer.class, unresolvedId));
        } finally {
            jdbc.update("DELETE FROM semantic_call_sites WHERE id=?", unresolvedId);
        }
    }

    private String dependencyCommit(UUID repository) {
        return jdbc.queryForObject("SELECT commit_sha FROM index_generations WHERE repository_id=? AND published",String.class,repository);
    }

    private void onboard(String name, Path source, String type) {
        RepositoryRecord repo = repositories.insert("Fixture", name, source.toUri().toString(), TEAM, "main", type,
                ROOT.resolve("mirrors/Fixture").resolve(name).toString());
        jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES(?,?) ON CONFLICT DO NOTHING", TEAM, repo.id());
    }

    private void automaticallyOnboardAndAwait(String name,Path source) throws Exception {
        var client=HttpClient.newHttpClient();
        URI endpoint=URI.create("http://localhost:"+port+"/api/repositories");
        // Omit defaultBranch: onboarding must discover the remote's symbolic HEAD.
        String body=json.writeValueAsString(Map.of("owner","Fixture","name",name,"cloneUrl",source.toUri().toString(),"type","SERVICE"));
        var request=HttpRequest.newBuilder(endpoint).header("X-API-Key","integration-admin-token")
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var teamOnly=client.send(HttpRequest.newBuilder(endpoint).header("X-API-Key",API_KEY)
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(403,teamOnly.statusCode(),"a team key must not gain administrative onboarding permission");
        String invalidBody=json.writeValueAsString(Map.of("owner","Fixture","name","invalid-onboarding","cloneUrl","https://127.0.0.1/secret/repo.git"));
        var invalid=client.send(HttpRequest.newBuilder(endpoint).header("X-API-Key","integration-admin-token")
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(invalidBody)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(400,invalid.statusCode(),invalid.body());
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM repositories WHERE name='invalid-onboarding'",Integer.class));
        var first=client.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,first.statusCode(),"automatic onboarding failed: "+first.body());
        UUID repository=UUID.fromString(json.readTree(first.body()).path("id").asText());
        assertEquals("main",json.readTree(first.body()).path("defaultBranch").asText());
        try(var workers=java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var duplicates=new java.util.ArrayList<java.util.concurrent.Future<HttpResponse<String>>>();
            for(int i=0;i<4;i++)duplicates.add(workers.submit(()->client.send(request,HttpResponse.BodyHandlers.ofString())));
            for(var duplicate:duplicates) {
                var response=duplicate.get(30,java.util.concurrent.TimeUnit.SECONDS);
                assertEquals(200,response.statusCode(),response.body());
                assertEquals(repository.toString(),json.readTree(response.body()).path("id").asText());
            }
        }
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM repository_onboarding WHERE repository_id=?",Integer.class,repository));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM index_jobs WHERE repository_id=? AND idempotency_key LIKE 'onboarding:%'",Integer.class,repository),
                "concurrent duplicate requests must schedule exactly one durable initial job");
        var conflicting=client.send(HttpRequest.newBuilder(endpoint).header("X-API-Key","integration-admin-token")
                .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body.replace("\"SERVICE\"","\"LIBRARY\""))).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(409,conflicting.statusCode(),"a duplicate request must not silently replace repository configuration");
        assertEquals("SERVICE",repositories.find("Fixture",name).orElseThrow().repositoryType());
        UUID job=jdbc.queryForObject("SELECT initial_job_id FROM repository_onboarding WHERE repository_id=?",UUID.class,repository);
        // No manual /index request or poll call: the normal worker must create the mirror and publish.
        awaitSucceeded(job,name);
        assertTrue(Files.isDirectory(ROOT.resolve("mirrors/Fixture").resolve(name).resolve(".git")));
        assertEquals(repositories.currentGeneration(repository).orElseThrow(),jdbc.queryForObject("SELECT generation_id FROM index_jobs WHERE id=?",UUID.class,job));
        var status=client.send(HttpRequest.newBuilder(URI.create(endpoint+"/Fixture/"+name+"/onboarding"))
                .header("X-API-Key",API_KEY).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,status.statusCode(),status.body());
        JsonNode onboarding=json.readTree(status.body());
        assertEquals("INDEXED",onboarding.path("state").asText());
        assertEquals("SUCCEEDED",onboarding.path("indexing_state").asText());
        assertEquals("NOT_APPLICABLE",onboarding.path("webhook_state").asText(),"file fixtures must not claim a live GitHub webhook");
        var hidden=client.send(HttpRequest.newBuilder(URI.create(endpoint+"/Fixture/"+name+"/onboarding"))
                .header("X-API-Key","isolated-integration-token").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(404,hidden.statusCode(),"onboarding status must not expose another team's repository/job metadata");
        // Controlled signed HTTP delivery, not a claim of live GitHub acceptance. Provider casing
        // must map to the existing canonical registry identity without changing that identity.
        String sha;try(Git sourceGit=Git.open(source.toFile())){sha=sourceGit.getRepository().resolve("HEAD").name();}
        String push=json.writeValueAsString(Map.of("after",sha,"ref","refs/heads/main","head_commit",Map.of("timestamp",java.time.Instant.now().toString()),
                "repository",Map.of("full_name","fixture/"+name)));
        var mac=javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(config.webhookSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8),"HmacSHA256"));
        String signature="sha256="+java.util.HexFormat.of().formatHex(mac.doFinal(push.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        UUID delivery=UUID.randomUUID();
        var hookRequest=HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/webhooks/github"))
                .header("Content-Type","application/json").header("X-Hub-Signature-256",signature)
                .header("X-GitHub-Delivery",delivery.toString()).header("X-GitHub-Event","push")
                .POST(HttpRequest.BodyPublishers.ofString(push)).build();
        var delivered=client.send(hookRequest,HttpResponse.BodyHandlers.ofString());
        assertEquals(202,delivered.statusCode(),delivered.body());
        UUID pushJob=UUID.fromString(json.readTree(delivered.body()).path("jobId").asText());
        var replay=client.send(hookRequest,HttpResponse.BodyHandlers.ofString());
        assertEquals(202,replay.statusCode(),replay.body());assertTrue(json.readTree(replay.body()).path("duplicate").asBoolean());
        awaitSucceeded(pushJob,name);
        assertEquals(repositories.currentGeneration(repository).orElseThrow(),jdbc.queryForObject("SELECT generation_id FROM index_jobs WHERE id=?",UUID.class,pushJob));
        String fingerprint=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(config.webhookSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(fingerprint,jdbc.queryForObject("SELECT signing_key_fingerprint FROM webhook_deliveries WHERE delivery_id=?",String.class,delivery));
        assertEquals("Fixture/"+name,repositories.find("Fixture",name).orElseThrow().key());
    }

    private void indexAndAwait(String name) throws Exception {
        String sha;
        try (Git source = Git.open(ROOT.resolve("git-sources").resolve(name).toFile())) { sha = source.getRepository().resolve("HEAD").name(); }
        indexAndAwait(name,"integration-"+name+"-"+sha);
    }

    private void indexAndAwait(String name,String delivery) throws Exception {
        String sha;
        try (Git source = Git.open(ROOT.resolve("git-sources").resolve(name).toFile())) { sha = source.getRepository().resolve("HEAD").name(); }
        UUID job = indexing.enqueue("Fixture", name, sha, delivery);
        indexing.poll();
        awaitSucceeded(job,name);
    }

    private void awaitSucceeded(UUID job,String name) throws Exception {
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
        String route=version.equals("1.0")?"/orders/{id}":"/orders/v2/{id}";
        String topic=version.equals("1.0")?"order-events":"order-events-v2";
        String openApiRequired = version.equals("1.0") ? "[id]" : "[id, owner]";
        String requestEnum = version.equals("1.0") ? "[A, B]" : "[A]";
        String responseEnum = version.equals("1.0") ? "[A]" : "[A, B]";
        String openApiOwnerProp = version.equals("1.0") ? "" : "                        owner: { type: string }\n";
        String avroFields = version.equals("1.0")
                ? "[{\"name\":\"id\",\"type\":\"string\"}]"
                : "[{\"name\":\"id\",\"type\":\"string\"},{\"name\":\"owner\",\"type\":\"string\"}]";
        var files = new java.util.LinkedHashMap<String,String>();
        files.put("pom.xml", pom(version, null).replace("fixture","org.acme"));
        files.put("docs/fallback-notes.txt",java.util.stream.IntStream.rangeClosed(1,121)
                .mapToObj(line->"Fallback fixture note "+line).collect(java.util.stream.Collectors.joining("\n")));
        files.put("docs/guide.md","# Setup\nUse local tools.\n\n## Recovery\nRestore the saved snapshot.\n");
        files.put("config/runtime.yaml","server:\n  description: '"+"server ".repeat(450)+"'\nindexer:\n  description: '"+"indexer ".repeat(450)+"'\n");
        files.put("src/main/java/org/acme/Money.java", librarySource(api));
        files.put("samples/refund_policy.py","class RefundPolicy:\n    minimum = 0\n    def eligible(self, paid, cancelled):\n        return paid > self.minimum and not cancelled\n");
        files.put("src/main/java/org/acme/OrderResource.java", "package org.acme;\nclass OrderResource { @GetMapping(\""+route+"\") Object find(String id) { return null; } }\n");
        files.put("src/main/java/org/acme/OrderProducer.java", "package org.acme;\nclass OrderProducer { void publish() { kafkaTemplate.send(\""+topic+"\", \"payload\"); } }\n");
        files.put("openapi.yaml", """
                openapi: 3.0.3
                paths:
                  /catalog/{id}:
                    get:
                      parameters:
                        - name: id
                          in: path
                          required: true
                          schema: { type: string }
                        - name: state
                          in: query
                          schema: { type: string, enum: %s }
                      responses:
                        "200":
                          content:
                            application/json:
                              schema:
                                $ref: "#/components/schemas/CatalogItem"
                components:
                  schemas:
                    CatalogItem:
                      type: object
                      required: %s
                      properties:
                        id: { type: string }
%s                        tag: { type: string }
                        state: { type: string, enum: %s }
                """.formatted(requestEnum, openApiRequired, openApiOwnerProp, responseEnum));
        // Separate Avro-backed topic so annotation kafkaTemplate.send("order-events") remains
        // the single provider-consumer link asserted below.
        files.put("schemas/inventory-event.avsc",
                "{\"type\":\"record\",\"name\":\"InventoryEvent\",\"fields\":" + avroFields + "}");
        files.put("src/main/resources/application.properties", """
                mp.messaging.outgoing.inventory.topic=inventory-events
                mp.messaging.outgoing.inventory.schema=schemas/inventory-event.avsc
                """);
        return Map.copyOf(files);
    }

    private static Map<String,String> consumerFiles(String version, String className, String returnType, String invocation) {
        String unresolvedMethod = className.equals("ConsumerA")
                ? "  public String unresolved(Money money) {\n    return money.missing(\"x\");\n  }\n"
                : "";
        var files=new java.util.LinkedHashMap<String,String>();
        files.put("pom.xml", pom("1.0", "<dependencies><dependency><groupId>org.acme</groupId><artifactId>common-library</artifactId><version>"+version+"</version></dependency></dependencies>"));
        files.put("src/main/java/app/"+className+".java", "package app;\nimport org.acme.Money;\npublic class "+className+" {\n  public "+returnType+" call(Money money) {\n    return "+invocation+";\n  }\n"+unresolvedMethod+"}\n");
        if(className.equals("ConsumerA")) {
            files.put("src/main/java/app/EntryPoint.java", """
                    package app;
                    public class EntryPoint {
                      public String load(ConsumerA consumer, org.acme.Money money) {
                        return consumer.call(money);
                      }
                      public String request(ConsumerA consumer, org.acme.Money money) {
                        return load(consumer, money);
                      }
                    }
                    """);
            files.put("src/main/java/app/OrderClient.java", "package app;\nclass OrderClient { void load() { webClient.get().uri(\"/orders/{id}\"); } }\n");
            // Kotlin→Java cross-repo caller of the same removed overload; must surface as a separate JVM edge.
            files.put("src/main/kotlin/app/ConsumerAKotlin.kt", "package app\nclass ConsumerAKotlin { fun call(money: org.acme.Money)=money.convert(\"x\") }\n");
        }
        if(className.equals("ConsumerB"))files.put("src/main/java/app/OrderListener.java", "package app;\nclass OrderListener { @KafkaListener(topics = \"order-events\") void listen(String value) {} }\n");
        if(className.equals("ConsumerC")) {
            files.put("src/main/kotlin/app/ConsumerKotlinPeer.kt", "package app\nclass ConsumerKotlinPeer { fun label()=\"witness\"; fun call(money: org.acme.Money)=money.convert(1) }\n");
            files.put("src/main/java/app/ConsumerJavaPeer.java", "package app; class ConsumerJavaPeer { String call() { return new ConsumerKotlinPeer().label(); } }\n");
        }
        return Map.copyOf(files);
    }

    private void assertCrossRepositoryRestAndKafkaContracts(UUID provider,UUID restConsumer,UUID kafkaConsumer) {
        String restSql="""
                SELECT count(*) FROM contract_links l
                JOIN contracts p ON p.id=l.provider_contract JOIN index_generations pg ON pg.id=p.generation_id
                JOIN contracts c ON c.id=l.consumer_contract JOIN index_generations cg ON cg.id=c.generation_id
                WHERE pg.repository_id=? AND cg.repository_id=? AND p.contract_type='REST'
                  AND p.role='EXPORT' AND c.role='IMPORT' AND p.canonical_key='rest:GET:/orders/{id}'
                """;
        assertEquals(1,jdbc.queryForObject(restSql,Integer.class,provider,restConsumer),"REST contract must persist as an exact provider-consumer link");
        String kafkaSql="""
                SELECT count(*) FROM contract_links l
                JOIN contracts p ON p.id=l.provider_contract JOIN index_generations pg ON pg.id=p.generation_id
                JOIN contracts c ON c.id=l.consumer_contract JOIN index_generations cg ON cg.id=c.generation_id
                WHERE pg.repository_id=? AND cg.repository_id=? AND p.contract_type='KAFKA'
                  AND p.role='EXPORT' AND c.role='IMPORT' AND p.canonical_key='kafka:order-events'
                """;
        assertEquals(1,jdbc.queryForObject(kafkaSql,Integer.class,provider,kafkaConsumer),"Kafka contract must persist as an exact provider-consumer link");
        assertTrue(jdbc.queryForObject("SELECT min(l.confidence)>0 AND count(*)=2 FROM contract_links l JOIN contracts p ON p.id=l.provider_contract JOIN index_generations pg ON pg.id=p.generation_id WHERE pg.repository_id=? AND p.contract_type IN ('REST','KAFKA')",Boolean.class,provider));
        assertTrue(jdbc.queryForObject("""
                SELECT bool_and(l.evidence::text LIKE '%providerFile%' AND l.evidence::text LIKE '%consumerFile%'
                                AND l.evidence::text LIKE '%providerCommit%' AND l.evidence::text LIKE '%consumerCommit%')
                FROM contract_links l
                JOIN contracts p ON p.id=l.provider_contract JOIN index_generations pg ON pg.id=p.generation_id
                WHERE pg.repository_id=? AND p.contract_type IN ('REST','KAFKA')
                """,Boolean.class,provider),
                "persisted REST/Kafka links must retain provider and consumer file/commit evidence");
        assertTrue(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id AND g.published
                  WHERE g.repository_id=? AND c.contract_type='REST' AND c.role='EXPORT'
                    AND c.canonical_key='rest:GET:/catalog/{id}'
                    AND c.metadata->>'signature' LIKE 'restOp{%%}')
                """, Boolean.class, provider),
                "OpenAPI YAML nested $ref export must persist on the published provider generation");
        assertTrue(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM contracts c JOIN index_generations g ON g.id=c.generation_id AND g.published
                  WHERE g.repository_id=? AND c.contract_type='KAFKA' AND c.role='EXPORT'
                    AND c.canonical_key='kafka:inventory-events'
                    AND c.metadata->>'signature' LIKE 'fields{%%}')
                """, Boolean.class, provider),
                "Avro fields{} Kafka export must persist on the published provider generation");
    }

    private void verifyIncrementalAdditionChangeAndDeletion(Path consumerSource,UUID consumer)throws Exception {
        Path javaFile=consumerSource.resolve("src/main/java/app/ConsumerC.java");
        Path temporaryFile=consumerSource.resolve("src/main/java/app/Temporary.java");
        UUID initialGeneration=repositories.currentGeneration(consumer).orElseThrow();
        String unchangedClassChunk=jdbc.queryForObject("SELECT content FROM code_chunks WHERE generation_id=? AND file_path='src/main/java/app/ConsumerC.java' AND symbol='app.ConsumerC'",String.class,initialGeneration);
        Files.writeString(javaFile,Files.readString(javaFile).replace("    return money.convert(Integer.valueOf(1));",
                "    // changed source must be reprocessed\n    return money.convert(Integer.valueOf(1));"));
        Files.writeString(temporaryFile,"package app; class Temporary { int value() { return 1; } }\n");
        try(Git git=Git.open(consumerSource.toFile())){commit(git,"incremental source change and addition");}
        org.mockito.Mockito.clearInvocations(embeddingModel);
        indexAndAwait("consumer-service-c");
        UUID changedGeneration=repositories.currentGeneration(consumer).orElseThrow();
        assertNotEquals(initialGeneration,changedGeneration);
        var embeddedInputs=embeddedInputs();
        assertEquals(3,embeddedInputs.size(),"only the changed method and new file's class/method must call real Ollama");
        assertFalse(embeddedInputs.contains(unchangedClassChunk),"unchanged chunk inside the edited file must reuse its vector");
        assertTrue(embeddedInputs.stream().anyMatch(text->text.contains("// changed source must be reprocessed")));
        assertEquals(unchangedClassChunk,jdbc.queryForObject("SELECT content FROM code_chunks WHERE generation_id=? AND file_path='src/main/java/app/ConsumerC.java' AND symbol='app.ConsumerC'",String.class,changedGeneration));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM generation_files WHERE generation_id=? AND file_path='src/main/java/app/Temporary.java'",Integer.class,changedGeneration));
        Set<String> changedChunkHashes=new java.util.HashSet<>(jdbc.queryForList("SELECT content_hash FROM code_chunks WHERE generation_id=? AND file_path='src/main/java/app/ConsumerC.java'",String.class,changedGeneration));
        assertTrue(changedChunkHashes.size()>0,"changed Java method must be chunked again");

        Files.delete(temporaryFile);
        try(Git git=Git.open(consumerSource.toFile())){commit(git,"incremental source deletion");}
        indexAndAwait("consumer-service-c");
        UUID deletedGeneration=repositories.currentGeneration(consumer).orElseThrow();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM generation_files WHERE generation_id=? AND file_path='src/main/java/app/Temporary.java'",Integer.class,deletedGeneration));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path='src/main/java/app/Temporary.java'",Integer.class,deletedGeneration));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=? AND file_path='src/main/java/app/Temporary.java'",Integer.class,deletedGeneration));
        Set<String> afterDeletionHashes=new java.util.HashSet<>(jdbc.queryForList("SELECT content_hash FROM code_chunks WHERE generation_id=? AND file_path='src/main/java/app/ConsumerC.java'",String.class,deletedGeneration));
        assertEquals(changedChunkHashes,afterDeletionHashes,"unchanged Java chunks and vectors must be reused across the deletion-only generation");

        // File rename must drop the old path and publish the new path without retaining stale nodes/chunks.
        Path renamedFile=consumerSource.resolve("src/main/java/app/ConsumerCRenamed.java");
        Files.move(javaFile,renamedFile);
        String renamedSource=Files.readString(renamedFile).replace("class ConsumerC","class ConsumerCRenamed");
        Files.writeString(renamedFile,renamedSource);
        try(Git git=Git.open(consumerSource.toFile())){commit(git,"incremental source rename");}
        indexAndAwait("consumer-service-c");
        UUID renamedGeneration=repositories.currentGeneration(consumer).orElseThrow();
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM generation_files WHERE generation_id=? AND file_path='src/main/java/app/ConsumerC.java'",Integer.class,renamedGeneration));
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM generation_files WHERE generation_id=? AND file_path='src/main/java/app/ConsumerCRenamed.java'",Integer.class,renamedGeneration));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path='src/main/java/app/ConsumerC.java'",Integer.class,renamedGeneration));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_nodes WHERE generation_id=? AND file_path='src/main/java/app/ConsumerCRenamed.java'",Integer.class,renamedGeneration)>0);

        // Dependency-only change (no Java source edit) must refresh Maven coordinates while preserving renamed source records.
        Path pom=consumerSource.resolve("pom.xml");
        String pomText=Files.readString(pom);
        assertTrue(pomText.contains("<version>1.0</version>")||pomText.contains("<version>2.0</version>"));
        // Bump a non-provider property comment marker so the blob changes without altering call sites.
        Files.writeString(pom,pomText.replace("</project>","<!-- dependency refresh marker --></project>"));
        try(Git git=Git.open(consumerSource.toFile())){commit(git,"incremental dependency metadata refresh");}
        indexAndAwait("consumer-service-c");
        UUID dependencyGeneration=repositories.currentGeneration(consumer).orElseThrow();
        assertNotEquals(renamedGeneration,dependencyGeneration);
        assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM generation_files WHERE generation_id=? AND file_path='src/main/java/app/ConsumerCRenamed.java'",Integer.class,dependencyGeneration));

        List<String> incrementalSnapshot=indexedGenerationSnapshot(dependencyGeneration);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=? AND embedding IS NOT NULL",Integer.class,dependencyGeneration)>0);
        jdbc.update("UPDATE contract_index_metadata SET generator_version='force-full-rebuild' WHERE generation_id=?",dependencyGeneration);
        jdbc.update("DELETE FROM generation_files WHERE generation_id=?",dependencyGeneration);
        indexAndAwait("consumer-service-c","full-rebuild-equivalence-"+UUID.randomUUID());
        UUID rebuiltGeneration=repositories.currentGeneration(consumer).orElseThrow();
        assertNotEquals(dependencyGeneration,rebuiltGeneration);
        assertEquals(incrementalSnapshot,indexedGenerationSnapshot(rebuiltGeneration),
                "full reprocessing and incremental indexing must produce the same stable records");

        // A generator upgrade must rebuild cached source even when the Git blobs are unchanged.
        jdbc.update("UPDATE contract_index_metadata SET generator_version='legacy-chunk-generator' WHERE generation_id=?",rebuiltGeneration);
        jdbc.update("UPDATE code_chunks SET content_hash='legacy-poisoned-chunk',content='legacy content' WHERE id=(SELECT id FROM code_chunks WHERE generation_id=? ORDER BY id LIMIT 1)",rebuiltGeneration);
        indexAndAwait("consumer-service-c","generator-upgrade-"+UUID.randomUUID());
        UUID upgradedGeneration=repositories.currentGeneration(consumer).orElseThrow();
        assertNotEquals(rebuiltGeneration,upgradedGeneration);
        assertEquals(incrementalSnapshot,indexedGenerationSnapshot(upgradedGeneration),
                "generator upgrade must not copy legacy chunks merely because file blobs match");

        // A different/legacy embedding space must force real embedding even for identical Git blobs.
        int expectedEmbeddings=jdbc.queryForObject("SELECT count(*) FROM code_chunks WHERE generation_id=?",Integer.class,upgradedGeneration);
        jdbc.update("UPDATE index_generations SET embedding_fingerprint=? WHERE id=?","0".repeat(64),upgradedGeneration);
        org.mockito.Mockito.clearInvocations(embeddingModel);
        indexAndAwait("consumer-service-c","embedding-space-upgrade-"+UUID.randomUUID());
        UUID spaceGeneration=repositories.currentGeneration(consumer).orElseThrow();
        assertNotEquals(upgradedGeneration,spaceGeneration);
        assertEquals(expectedEmbeddings,embeddedInputs().size(),"embedding-space mismatch must re-embed every source chunk");
        assertEquals(incrementalSnapshot,indexedGenerationSnapshot(spaceGeneration));
        assertTrue(jdbc.queryForObject("SELECT embedding_fingerprint ~ '^[0-9a-f]{64}$' AND embedding_fingerprint<>? AND embedding_metadata->>'digest' ~ '^[0-9a-f]{64}$' AND (embedding_metadata->>'dimensions')::int=1024 FROM index_generations WHERE id=?",Boolean.class,"0".repeat(64),spaceGeneration));
    }

    private List<String> embeddedInputs() {
        return org.mockito.Mockito.mockingDetails(embeddingModel).getInvocations().stream()
                .filter(invocation->invocation.getMethod().getName().equals("embed")&&invocation.getArguments().length==1&&invocation.getArgument(0) instanceof String)
                .map(invocation->invocation.<String>getArgument(0)).toList();
    }

    private List<String> indexedGenerationSnapshot(UUID generation) {
        var snapshot=new java.util.ArrayList<String>();
        snapshot.addAll(jdbc.queryForList("SELECT 'file:'||file_path||':'||blob_sha FROM generation_files WHERE generation_id=? ORDER BY file_path",String.class,generation));
        snapshot.addAll(jdbc.queryForList("SELECT 'node:'||node_type||':'||symbol||':'||coalesce(qualified_name,'')||':'||file_path||':'||start_line||':'||end_line||':'||coalesce(language,'') FROM code_nodes WHERE generation_id=? ORDER BY file_path,start_line,node_type,symbol",String.class,generation));
        snapshot.addAll(jdbc.queryForList("SELECT 'chunk:'||file_path||':'||symbol||':'||start_line||':'||end_line||':'||content_hash||':'||coalesce(embedding::text,'') FROM code_chunks WHERE generation_id=? ORDER BY file_path,start_line,symbol",String.class,generation));
        snapshot.addAll(jdbc.queryForList("SELECT 'call:'||file_path||':'||coalesce(caller_class,'<unknown>')||':'||coalesce(caller_method,'<unknown>')||':'||invocation||':'||resolution_status||':'||coalesce(resolved_signature,'') FROM semantic_call_sites WHERE generation_id=? ORDER BY file_path,start_line,caller_class,caller_method,invocation,resolution_status,resolved_signature",String.class,generation));
        snapshot.addAll(jdbc.queryForList("SELECT 'semantic-edge:'||caller.symbol_key||':'||coalesce(target.symbol_key,'')||':'||edge.resolution_status FROM semantic_call_edges edge JOIN semantic_symbols caller ON caller.id=edge.caller_symbol_id LEFT JOIN semantic_symbols target ON target.id=edge.target_symbol_id WHERE edge.generation_id=? ORDER BY caller.symbol_key,target.symbol_key,edge.resolution_status",String.class,generation));
        snapshot.addAll(jdbc.queryForList("SELECT 'contract:'||contract_type||':'||role||':'||canonical_key||':'||file_path FROM contracts WHERE generation_id=? ORDER BY contract_type,role,canonical_key,file_path",String.class,generation));
        snapshot.addAll(jdbc.queryForList("SELECT 'classpath:'||module_path||':'||coalesce(group_id,'')||':'||coalesce(artifact_id,'')||':'||coalesce(version,'')||':'||resolution_status||':'||coalesce(classpath_fingerprint,'') FROM maven_module_classpaths WHERE generation_id=? ORDER BY module_path,group_id,artifact_id,version",String.class,generation));
        snapshot.addAll(jdbc.queryForList("SELECT 'dependency:'||module_path||':'||group_id||':'||artifact_id||':'||version||':'||scope||':'||coalesce(artifact_sha256,'') FROM maven_module_dependencies WHERE generation_id=? ORDER BY module_path,group_id,artifact_id,version,scope",String.class,generation));
        snapshot.add("contract-index:"+jdbc.queryForObject("SELECT index_hash FROM contract_index_metadata WHERE generation_id=?",String.class,generation));
        return List.copyOf(snapshot);
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
        Path jarFile = artifactDir.resolve("common-library-"+version+".jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(jarFile));
             var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\','/')));
                Files.copy(file,jar); jar.closeEntry();
            }
        }
        Path pomFile = artifactDir.resolve("common-library-"+version+".pom");
        Files.writeString(pomFile,pom(version,null).replace("fixture","org.acme"));
        writeChecksumSidecars(jarFile);
        writeChecksumSidecars(pomFile);
    }


    private static String kotlinLibraryPom(String version) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>org.acme</groupId>"
                + "<artifactId>kotlin-common-library</artifactId><version>"+version+"</version>"
                + "<packaging>jar</packaging></project>";
    }

    private static String kotlinLibrarySourceV1() {
        return """
                package org.acme.kotlin
                class Amount(val label: String) {
                    fun convert(value: String): String = value
                    fun convert(value: Int): String = value.toString()
                }
                fun String.decorate(): String = this + "!"
                fun entryPoint(): String = "ok"
                """;
    }

    private static String kotlinLibrarySourceV2() {
        return """
                package org.acme.kotlin
                class Amount(val label: Int) {
                    fun convert(value: java.math.BigDecimal): String = value.toString()
                    fun convert(value: Int): String = value.toString()
                }
                """;
    }

    private static String amountJavaPeerSource() {
        return "package org.acme.kotlin;\npublic class AmountJavaPeer {\n  public static String peer() { return \"peer\"; }\n}\n";
    }

    private static Map<String,String> kotlinLibraryFiles(String version, String kotlinSource) {
        var files = new java.util.LinkedHashMap<String,String>();
        files.put("pom.xml", kotlinLibraryPom(version));
        files.put("src/main/kotlin/org/acme/kotlin/Amount.kt", kotlinSource);
        files.put("src/main/java/org/acme/kotlin/AmountJavaPeer.java", amountJavaPeerSource());
        return Map.copyOf(files);
    }

    private static Map<String,String> kotlinConsumerFiles(String version, boolean kotlin, String className, String body) {
        var files = new java.util.LinkedHashMap<String,String>();
        files.put("pom.xml", "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId>"
                + "<artifactId>kotlin-consumer</artifactId><version>1.0</version>"
                + "<dependencies><dependency><groupId>org.acme</groupId>"
                + "<artifactId>kotlin-common-library</artifactId><version>"+version+"</version>"
                + "</dependency></dependencies></project>");
        if (kotlin) {
            files.put("src/main/kotlin/app/"+className+".kt",
                    "package app\nclass "+className+" { "+body+" }\n");
        } else {
            files.put("src/main/java/app/"+className+".java",
                    "package app;\npublic class "+className+" {\n  public String call(org.acme.kotlin.Amount amount) {\n    "+body+"\n  }\n}\n");
        }
        return Map.copyOf(files);
    }

    private static void publishKotlinLibraryArtifact(Path repository, String version, String kotlinSource) throws Exception {
        Path artifactDir = repository.resolve("org/acme/kotlin-common-library").resolve(version);
        Files.createDirectories(artifactDir);
        Path work = ROOT.resolve("compiled-kotlin-library").resolve(version);
        Path classes = work.resolve("classes");
        if (Files.exists(work)) try (var paths = Files.walk(work)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception e) { throw new IllegalStateException(e); }
            });
        }
        Files.createDirectories(classes);
        Path kotlinFile = work.resolve("Amount.kt");
        Path javaFile = work.resolve("AmountJavaPeer.java");
        Files.writeString(kotlinFile, kotlinSource);
        Files.writeString(javaFile, amountJavaPeerSource());
        String compiler = System.getenv().getOrDefault("KOTLIN_COMPILER",
                Path.of("target/kotlinc/bin/kotlinc").toAbsolutePath().toString());
        var command = new ProcessBuilder(compiler, "-jvm-target", "21", "-d", classes.toString(),
                kotlinFile.toAbsolutePath().toString());
        // kotlinc's launcher treats KOTLIN_COMPILER as its Java main class; never inherit it.
        command.environment().keySet().removeIf(key -> Set.of(
                "KOTLIN_COMPILER","KOTLIN_OPTS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","BASH_ENV",
                "DATABASE_PASSWORD","DATABASE_URL","GIT_ACCESS_TOKEN").contains(key));
        command.redirectErrorStream(true);
        Process process = command.start();
        String diagnostic = new String(process.getInputStream().readAllBytes());
        assertTrue(process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS), "kotlinc timed out publishing "+version);
        assertEquals(0, process.exitValue(), "kotlinc failed publishing kotlin-common-library:"+version+": "+diagnostic);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "-d", classes.toString(), javaFile.toAbsolutePath().toString()),
                "javac failed publishing AmountJavaPeer for "+version);
        Path jarFile = artifactDir.resolve("kotlin-common-library-"+version+".jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(jarFile));
             var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                jar.putNextEntry(new java.util.jar.JarEntry(classes.relativize(file).toString().replace('\\','/')));
                Files.copy(file, jar);
                jar.closeEntry();
            }
        }
        Path pomFile = artifactDir.resolve("kotlin-common-library-"+version+".pom");
        Files.writeString(pomFile, kotlinLibraryPom(version));
        writeChecksumSidecars(jarFile);
        writeChecksumSidecars(pomFile);
    }

    private JsonNode mcpImpactAnalysisOnly(String repository) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://localhost:"+port+"/mcp");
        Object initialize = Map.of("jsonrpc","2.0","id",1,"method","initialize","params",Map.of(
                "protocolVersion","2025-03-26","capabilities",Map.of(),
                "clientInfo",Map.of("name","postgres-kotlin-impact-it","version","1.0")));
        JsonNode initialized = post(client, endpoint, null, initialize);
        assertTrue(initialized.path("result").has("serverInfo"), initialized.toString());
        String session = lastResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(session, "Streamable HTTP initialize must return a session identifier");
        post(client, endpoint, session, Map.of("jsonrpc","2.0","method","notifications/initialized"));
        return post(client, endpoint, session, Map.of("jsonrpc","2.0","id",2,"method","tools/call","params",
                Map.of("name","impact_analysis","arguments",Map.of("contract","","repository",repository))));
    }

    private JsonNode mcpImpactCallWithProposedChange(String repository, String oldSignature, String newSignature) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://localhost:"+port+"/mcp");
        Object initialize = Map.of("jsonrpc","2.0","id",1,"method","initialize","params",Map.of(
                "protocolVersion","2025-03-26","capabilities",Map.of(),
                "clientInfo",Map.of("name","postgres-proposed-change-it","version","1.0")));
        JsonNode initialized = post(client, endpoint, null, initialize);
        assertTrue(initialized.path("result").has("serverInfo"), initialized.toString());
        String session = lastResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(session, "Streamable HTTP initialize must return a session identifier");
        post(client, endpoint, session, Map.of("jsonrpc","2.0","method","notifications/initialized"));
        return post(client, endpoint, session, Map.of("jsonrpc","2.0","id",3,"method","tools/call","params",
                Map.of("name","impact_analysis","arguments",Map.of(
                        "contract","","repository",repository,
                        "oldSignature",oldSignature,"newSignature",newSignature))));
    }

    private static void writeChecksumSidecars(Path artifact) throws Exception {
        byte[] content = Files.readAllBytes(artifact);
        for (String algorithm : List.of("SHA-1", "MD5")) {
            String extension = algorithm.equals("SHA-1") ? "sha1" : "md5";
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance(algorithm).digest(content));
            Files.writeString(Path.of(artifact + "." + extension), digest + "\n");
        }
    }

    private JsonNode mcpImpactCall(String repository) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        URI endpoint = URI.create("http://localhost:"+port+"/mcp");
        Object initialize=Map.of("jsonrpc","2.0","id",1,"method","initialize","params",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","postgres-impact-it","version","1.0")));
        HttpResponse<String> unauthenticated=exchange(client,endpoint,null,initialize,null);
        assertEquals(401,unauthenticated.statusCode(),"MCP must reject an unauthenticated client; headers="
                +unauthenticated.headers().map()+" body="+unauthenticated.body());
        UUID.fromString(unauthenticated.headers().firstValue("X-Request-ID").orElseThrow());
        HttpResponse<String> invalidCredentials=exchange(client,endpoint,null,initialize,"invalid-integration-token");
        assertEquals(401,invalidCredentials.statusCode(),"MCP must reject an invalid API key; headers="
                +invalidCredentials.headers().map()+" body="+invalidCredentials.body());
        String invalidRequestId = invalidCredentials.headers().firstValue("X-Request-ID").orElseThrow();
        UUID.fromString(invalidRequestId);
        assertNotEquals(unauthenticated.headers().firstValue("X-Request-ID").orElseThrow(), invalidRequestId);
        JsonNode initialized = post(client,endpoint,null,initialize);
        assertTrue(initialized.path("result").has("serverInfo"), initialized.toString());
        HttpResponse<String> initResponse = lastResponse;
        String session = initResponse.headers().firstValue("Mcp-Session-Id").orElse(null);
        assertNotNull(session,"Streamable HTTP initialize must return a session identifier");
        post(client,endpoint,session,Map.of("jsonrpc","2.0","method","notifications/initialized"));
        JsonNode tools = post(client,endpoint,session,Map.of("jsonrpc","2.0","id",2,"method","tools/list","params",Map.of()));
        JsonNode listedTools = tools.path("result").path("tools");
        assertTrue(listedTools.isArray(), "tools/list must return a tools array: " + tools);
        assertFalse(listedTools.isEmpty(), "tools/list must register at least one MCP tool: " + tools);
        java.util.Set<String> toolNames = new java.util.HashSet<>();
        listedTools.forEach(tool -> toolNames.add(tool.path("name").asText()));
        assertTrue(toolNames.containsAll(List.of("search_code","read_file","find_symbol","get_neighbors","semantic_search","impact_analysis")),
                "tools/list is missing expected Code Context tools: " + listedTools);
        JsonNode impactSchema = null;
        for (JsonNode tool : listedTools) {
            if ("impact_analysis".equals(tool.path("name").asText())) {
                impactSchema = tool.path("inputSchema");
                break;
            }
        }
        assertNotNull(impactSchema, "impact_analysis schema missing from tools/list");
        String required = impactSchema.path("required").toString();
        assertFalse(required.contains("oldSignature"), "oldSignature must remain optional: " + impactSchema);
        assertFalse(required.contains("newSignature"), "newSignature must remain optional: " + impactSchema);
        assertTrue(impactSchema.path("properties").has("oldSignature"), impactSchema.toString());
        assertTrue(impactSchema.path("properties").has("newSignature"), impactSchema.toString());
        awaitSearch(client,endpoint,session,3,"Money","exact search");
        awaitSearch(client,endpoint,session,4,"regex:money\\.convert\\(","regex search");
        JsonNode file=post(client,endpoint,session,Map.of("jsonrpc","2.0","id",5,"method","tools/call","params",Map.of("name","read_file","arguments",Map.of("repository","Fixture/consumer-service-a","path","src/main/java/app/ConsumerA.java","startLine",1,"endLine",6))));
        JsonNode fileEvidence=structuredToolPayload(file);
        assertEquals("Fixture/consumer-service-a",fileEvidence.path("repository").asText());
        assertEquals(repositories.currentCommit(repositories.id("Fixture","consumer-service-a")).orElseThrow(),fileEvidence.path("commit").asText());
        assertEquals("src/main/java/app/ConsumerA.java",fileEvidence.path("path").asText());
        assertTrue(fileEvidence.path("content").asText().contains("ConsumerA"));
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_nodes n JOIN index_generations g ON g.id=n.generation_id AND g.published WHERE n.symbol='ConsumerA' AND n.file_path LIKE '%ConsumerA.java'",Integer.class)>0,
                "published ConsumerA code_nodes must exist before find_symbol");
        JsonNode symbolCall=post(client,endpoint,session,Map.of("jsonrpc","2.0","id",6,"method","tools/call","params",Map.of("name","find_symbol","arguments",Map.of("symbol","ConsumerA","repository","Fixture/consumer-service-a"))));
        assertFalse(symbolCall.path("result").path("isError").asBoolean(false),"find_symbol MCP call failed: "+symbolCall);
        assertTrue(structuredToolPayload(symbolCall).toString().contains("ConsumerA.java"),"find_symbol must return indexed definitions and source path");
        JsonNode neighborCall=post(client,endpoint,session,Map.of("jsonrpc","2.0","id",7,"method","tools/call","params",Map.of("name","get_neighbors","arguments",Map.of("repository","Fixture/consumer-service-a","symbol","ConsumerA"))));
        assertFalse(neighborCall.path("result").path("isError").asBoolean(false),"get_neighbors MCP call failed: "+neighborCall);
        JsonNode neighbors=structuredToolPayload(neighborCall);
        assertTrue(neighbors.toString().contains("SEMANTIC_RESOLVED"),"get_neighbors must expose the persisted resolved call edge: "+neighbors);
        assertTrue(neighbors.toString().contains(repositories.currentCommit(repositories.id("Fixture","consumer-service-a")).orElseThrow()),"get_neighbors must include commit evidence: "+neighbors);
        JsonNode semantic=post(client,endpoint,session,Map.of("jsonrpc","2.0","id",8,"method","tools/call","params",Map.of("name","semantic_search","arguments",Map.of("query","a representation that changes character input into textual output","repository","Fixture/common-library","limit",50))));
        assertFalse(semantic.path("result").path("isError").asBoolean(false),"semantic_search MCP call failed: "+semantic);
        JsonNode semanticResults=structuredToolPayload(semantic);
        assertTrue(semanticResults.isArray(),"semantic_search should return JSON result rows: "+semanticResults);
        assertTrue(semanticResults.toString().contains("src/main/java/org/acme/Money.java"),"semantic retrieval missed the matching method: "+semanticResults);
        assertTrue(semanticResults.toString().contains("convert"),"semantic retrieval omitted symbol evidence: "+semanticResults);
        assertTrue(semanticResults.toString().contains(repositories.currentCommit(repositories.id("Fixture","common-library")).orElseThrow()),"semantic retrieval omitted commit provenance: "+semanticResults);
        assertTrue(jdbc.queryForObject("SELECT count(*) FROM code_chunks c JOIN index_generations g ON g.id=c.generation_id AND g.published WHERE g.repository_id=? AND c.embedding IS NOT NULL",Integer.class,repositories.id("Fixture","common-library"))>0,"real Ollama vectors must be persisted in PostgreSQL");
        JsonNode refund=post(client,endpoint,session,Map.of("jsonrpc","2.0","id",82,"method","tools/call","params",Map.of("name","semantic_search","arguments",Map.of("query","determine whether a paid transaction can be refunded when it has not been cancelled","repository","Fixture/common-library","limit",10))));
        assertFalse(refund.path("result").path("isError").asBoolean(false),"natural-language refund retrieval must succeed through HTTP MCP");
        assertTrue(structuredToolPayload(refund).toString().contains("samples/refund_policy.py"),"independently labelled refund question must retrieve its Python implementation: "+refund);
        UUID semanticGeneration=repositories.currentGeneration(repositories.id("Fixture","common-library")).orElseThrow();
        String fingerprint=jdbc.queryForObject("SELECT embedding_fingerprint FROM index_generations WHERE id=?",String.class,semanticGeneration);
        assertNotNull(fingerprint);assertTrue(fingerprint.matches("[0-9a-f]{64}"));
        jdbc.update("UPDATE index_generations SET embedding_fingerprint=NULL WHERE id=?",semanticGeneration);
        try {
            var stale=post(client,endpoint,session,Map.of("jsonrpc","2.0","id",81,"method","tools/call","params",Map.of("name","semantic_search","arguments",Map.of("query","money conversion","repository","Fixture/common-library","limit",10))));
            assertTrue(stale.path("result").path("isError").asBoolean(false)||stale.path("error").isObject(),"legacy vectors must require reindexing instead of mixing embedding spaces: "+stale);
        } finally {jdbc.update("UPDATE index_generations SET embedding_fingerprint=? WHERE id=?",fingerprint,semanticGeneration);}
        verifyOtherTeamIsolation(endpoint);
        return post(client,endpoint,session,Map.of("jsonrpc","2.0","id",9,"method","tools/call","params",Map.of("name","impact_analysis","arguments",Map.of("contract","","repository",repository))));
    }

    private void verifyOtherTeamIsolation(URI endpoint)throws Exception {
        var client=HttpClient.newHttpClient();
        String key="isolated-integration-token";
        var initialized=post(client,endpoint,null,Map.of("jsonrpc","2.0","id",1000,"method","initialize","params",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","isolated-team-it","version","1.0"))),key);
        assertTrue(initialized.path("result").has("serverInfo"),initialized.toString());
        String isolatedSession=lastResponse.headers().firstValue("Mcp-Session-Id").orElseThrow();
        post(client,endpoint,isolatedSession,Map.of("jsonrpc","2.0","method","notifications/initialized"),key);
        var arguments=new java.util.LinkedHashMap<String,Map<String,Object>>();
        arguments.put("search_code",Map.of("query","Money"));
        arguments.put("find_symbol",Map.of("symbol","Money","repository","Fixture/common-library"));
        arguments.put("read_file",Map.of("repository","Fixture/common-library","path","src/main/java/org/acme/Money.java","startLine",1,"endLine",5));
        arguments.put("get_neighbors",Map.of("repository","Fixture/consumer-service-a","symbol","ConsumerA"));
        arguments.put("semantic_search",Map.of("query","money conversion","repository","Fixture/common-library","limit",10));
        arguments.put("impact_analysis",Map.of("contract","common-library","repository","Fixture/common-library"));
        int id=1001;
        for(var entry:arguments.entrySet()) {
            var response=post(client,endpoint,isolatedSession,Map.of("jsonrpc","2.0","id",id++,"method","tools/call","params",Map.of("name",entry.getKey(),"arguments",entry.getValue())),key);
            if(entry.getKey().equals("search_code"))assertTrue(structuredToolPayload(response).path("results").isEmpty(),response.toString());
            else if(entry.getKey().equals("find_symbol"))assertTrue(structuredToolPayload(response).isArray()&&structuredToolPayload(response).isEmpty(),response.toString());
            else assertTrue(response.path("result").path("isError").asBoolean(false)||response.path("error").isObject(),"foreign repository tool must reject access: "+entry.getKey()+" "+response);
            assertFalse(response.toString().contains("return money.convert"),"cross-team response leaked source content: "+entry.getKey());
        }
    }

    private void awaitSearch(HttpClient client,URI endpoint,String session,int id,String query,String description)throws Exception {
        AssertionError lastFailure=null;
        for(int attempt=0;attempt<40;attempt++) {
            JsonNode response=post(client,endpoint,session,Map.of("jsonrpc","2.0","id",id,"method","tools/call","params",Map.of("name","search_code","arguments",Map.of("query",query))));
            try { assertSearchResponse(response,description); return; }
            catch(AssertionError failure) { lastFailure=failure; Thread.sleep(500); }
        }
        throw lastFailure==null?new AssertionError(description+" did not return indexed results"):lastFailure;
    }

    private boolean zoektGenerationHasMatches(String repository,UUID generation)throws Exception {
        return zoektGenerationHasMatches(repository,generation,true);
    }

    private boolean zoektGenerationHasMatches(String repository,UUID generation,boolean expected)throws Exception {
        String zoektUrl=System.getenv().getOrDefault("ZOEKT_WEB_SERVER","http://localhost:6070");
        String query="convert repo:^"+repository.replace(".","\\.")+"@"+generation+"$";
        String request=json.writeValueAsString(Map.of("Q",query,"Opts",Map.of("MaxDocDisplayCount",10)));
        HttpRequest httpRequest=HttpRequest.newBuilder(URI.create(zoektUrl+"/api/search"))
                .header(HttpHeaders.CONTENT_TYPE,MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(request)).build();
        var client=HttpClient.newHttpClient();
        boolean found=false;
        // Shard publication/removal is observed asynchronously by the real Zoekt watcher.
        for(int attempt=0;attempt<60;attempt++) {
            HttpResponse<String> response=client.send(httpRequest,HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),"Zoekt generation verification failed: "+response.body());
            JsonNode result=json.readTree(response.body()).path("Result");
            assertTrue(result.isObject(),"Zoekt must return the pinned API Result envelope: "+response.body());
            found=result.path("Files").isArray()&&!result.path("Files").isEmpty();
            if(found==expected)return found;
            Thread.sleep(250);
        }
        return found;
    }

    private void assertSearchResponse(JsonNode toolResponse,String description)throws Exception {
        assertFalse(toolResponse.path("isError").asBoolean(false),description+" MCP call failed: "+toolResponse);
        JsonNode payload=structuredToolPayload(toolResponse);
        assertEquals("COMPLETE",payload.path("status").asText(),description+" did not complete: "+payload);
        assertTrue(payload.path("errors").isEmpty(),description+" returned service errors: "+payload);
        String results=payload.path("results").toString();
        String consumerCommit=repositories.currentCommit(repositories.id("Fixture","consumer-service-a")).orElseThrow();
        assertTrue(results.contains("Fixture/consumer-service-a"),description+" omitted authorized repository results: "+payload);
        assertTrue(results.contains("ConsumerA.java"),description+" omitted exact file evidence: "+payload);
        assertTrue(results.contains(consumerCommit),description+" omitted exact generation commit evidence: "+payload);
        JsonNode consumerFile=null;
        for(JsonNode result:payload.path("results")) {
            JsonNode files=result.path("matches").path("Files");
            if(!files.isArray())continue;
            for(JsonNode file:files) {
                if(file.path("FileName").asText().endsWith("ConsumerA.java")) {
                    consumerFile=file;
                    break;
                }
            }
            if(consumerFile!=null)break;
        }
        assertNotNull(consumerFile,description+" omitted ConsumerA.java line evidence: "+payload);
        assertTrue(consumerFile.path("LineMatches").isArray()&&!consumerFile.path("LineMatches").isEmpty(),
                description+" omitted matching source lines: "+consumerFile);
    }

    private HttpResponse<String> lastResponse;
    private String protocolVersion;
    private JsonNode post(HttpClient client, URI endpoint, String session, Object body) throws Exception {
        return post(client,endpoint,session,body,API_KEY);
    }

    private JsonNode post(HttpClient client, URI endpoint, String session, Object body,String key) throws Exception {
        HttpResponse<String> response=exchange(client,endpoint,session,body,key);
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
        UUID.fromString(lastResponse.headers().firstValue("X-Request-ID").orElseThrow());
        lastResponse.headers().firstValue("Mcp-Protocol-Version").ifPresent(value->protocolVersion=value);
        return lastResponse;
    }

    private JsonNode structuredToolPayload(JsonNode response)throws Exception {
        assertFalse(response.path("result").path("isError").asBoolean(false)
                ||response.path("isError").asBoolean(false),"MCP tool returned an error payload: "+response);
        JsonNode structured=response.path("result").path("structuredContent");
        if(structured.isObject()||structured.isArray())return structured;
        String text=response.path("result").path("content").path(0).path("text").asText();
        assertFalse(text.isBlank(),"MCP tool returned neither structuredContent nor JSON text: "+response);
        try { return json.readTree(text); }
        catch(Exception parseFailure) {
            throw new AssertionError("MCP tool text was not JSON: "+text+" from "+response,parseFailure);
        }
    }

    private static void write(Path root,String path,String content) {
        try { Path output=root.resolve(path);Files.createDirectories(output.getParent());Files.writeString(output,content); }
        catch(Exception e) { throw new IllegalStateException(e); }
    }
}
