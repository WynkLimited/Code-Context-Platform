package com.wynk.codecontext.index;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.repo.RepositoryStore;
import com.wynk.codecontext.search.ZoektIndexer;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Two independent complete JVM applications: shared queue/lease, real services and HTTP MCP visibility. */
class ApplicationReplicaConsistencyPostgresIT {
    @TempDir Path temporary;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String REPOSITORY="fixture/replica-application";
    private static final String SOURCE="src/main/java/sample/Version.java";
    @Test void bothApplicationsReadOnlyPublishedGenerationsWhileAContenderWaits() throws Exception {
        try(var fixture=new Fixture(temporary.toRealPath())) {
            fixture.initialize();
            Path source=Files.createDirectories(fixture.root.resolve("sources/provider"));
            String before;
            try(Git git=Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
                writeVersion(source,"replicaRevisionOneMarker");before=commit(git,"baseline");
            }
            var leader=fixture.start("leader");
            var repository=new RepositoryStore(fixture.jdbc).insert("fixture","replica-application",source.toUri().toString(),"replica","main","LIBRARY",fixture.root.resolve("mirrors/fixture/replica-application").toString());
            fixture.repository=repository.id();
            fixture.jdbc.update("INSERT INTO team_repository_access(team,repository_id) VALUES('replica',?)",repository.id());
            fixture.awaitJob(fixture.enqueue(before));
            var first=Session.connect(leader.port());first.read(before,"replicaRevisionOneMarker");

            Files.createFile(leader.directory().resolve("pause"));
            String after;
            try(Git git=Git.open(source.toFile())) {writeVersion(source,"replicaRevisionTwoMarker");after=commit(git,"replacement");}
            UUID publisher=fixture.enqueue(after);
            await(()->Files.exists(leader.directory().resolve("paused")),60,"leader did not reach the real post-Zoekt publication boundary");
            UUID candidate=UUID.fromString(Files.readString(leader.directory().resolve("paused")));
            assertFalse(fixture.jdbc.queryForObject("SELECT published FROM index_generations WHERE id=?",Boolean.class,candidate));
            Timestamp heartbeat=fixture.jdbc.queryForObject("SELECT updated_at FROM index_jobs WHERE id=?",Timestamp.class,publisher);
            var follower=fixture.start("follower");
            assertNotEquals(leader.process().pid(),follower.process().pid());assertNotEquals(leader.port(),follower.port());
            var second=Session.connect(follower.port());
            UUID contender=fixture.enqueue(after);
            await(()->fixture.jdbc.queryForObject("SELECT state='QUEUED' AND attempt=0 AND coalesce(error='repository is already being indexed',false) FROM index_jobs WHERE id=?",Boolean.class,contender),15,"second application did not contend for the held repository lease");
            assertEquals(2,fixture.jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=?",Integer.class,repository.id()),"contender must not create another generation before acquiring ownership");
            await(()->fixture.zoektContains(candidate,"replicaRevisionTwoMarker"),20,"real Zoekt did not load the unpublished candidate shard");
            for(var session:List.of(first,second)) {
                session.read(before,"replicaRevisionOneMarker");
                var search=session.tool("search_code",Map.of("query","replicaRevisionTwoMarker"));
                assertEquals("COMPLETE",search.path("status").asText());assertTrue(search.path("errors").isEmpty());
                assertTrue(search.path("results").isEmpty(),"HTTP search must exclude the candidate even after its real shard loads: "+search);
            }
            // Production 30-second heartbeat runs while the indexing thread is held, in a real JVM.
            await(()->fixture.jdbc.queryForObject("SELECT updated_at>? AND state='RUNNING' FROM index_jobs WHERE id=?",Boolean.class,heartbeat,publisher),45,"full application did not renew the active job heartbeat");
            Files.createFile(leader.directory().resolve("resume"));
            fixture.awaitJob(publisher);fixture.awaitJob(contender);
            assertEquals(candidate,fixture.jdbc.queryForObject("SELECT id FROM index_generations WHERE repository_id=? AND published",UUID.class,repository.id()));
            assertEquals(2,fixture.jdbc.queryForObject("SELECT count(*) FROM index_generations WHERE repository_id=?",Integer.class,repository.id()),"same-commit contender must reuse the freshly published generation");
            for(var session:List.of(first,second)) {
                session.read(after,"replicaRevisionTwoMarker");
                var search=session.tool("search_code",Map.of("query","replicaRevisionTwoMarker"));
                assertEquals("COMPLETE",search.path("status").asText());assertTrue(search.path("errors").isEmpty());
                assertFalse(search.path("results").isEmpty());assertTrue(search.toString().contains(after));
            }
        }
    }
    private static void writeVersion(Path source,String value) throws Exception {
        Path file=source.resolve(SOURCE);Files.createDirectories(file.getParent());
        Files.writeString(file,"package sample; public class Version { public String value(){return \""+value+"\";} }\n");
    }
    private static String commit(Git git,String message) throws Exception {
        git.add().addFilepattern(".").call();return git.commit().setMessage(message).setAuthor("Fixture","fixture@example.test").setCommitter("Fixture","fixture@example.test").call().getName();
    }
    @FunctionalInterface private interface Check {boolean get() throws Exception;}
    private static void await(Check check,int seconds,String message) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(seconds).toNanos();
        while(System.nanoTime()<deadline){if(check.get())return;Thread.sleep(100);}
        fail(message);
    }
    private record Replica(Process process,Path directory,int port) {}
    private static final class Fixture implements AutoCloseable {
        final Path root;final String schema="replica_app_"+UUID.randomUUID().toString().replace("-","");
        final String user=System.getenv().getOrDefault("DATABASE_USER","code_context");
        final String password=System.getenv().getOrDefault("DATABASE_PASSWORD","integration_test_password");
        final String url=System.getenv().getOrDefault("DATABASE_URL","jdbc:postgresql://localhost:5432/code_context_test");
        final String isolated=url+(url.contains("?")?"&":"?")+"currentSchema="+schema+",public";
        final JdbcTemplate admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        final JdbcTemplate jdbc=new JdbcTemplate(new DriverManagerDataSource(isolated,user,password));
        final List<Process> processes=new ArrayList<>();final List<Path> directories=new ArrayList<>();
        UUID repository;boolean schemaCreated;
        Fixture(Path root){this.root=root;}
        void initialize() {
            assertTrue(admin.queryForObject("SELECT current_database()",String.class).endsWith("_test"));
            admin.execute("CREATE EXTENSION IF NOT EXISTS vector WITH SCHEMA public");
            admin.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto WITH SCHEMA public");
            admin.execute("CREATE SCHEMA "+schema);
            schemaCreated=true;
        }
        Replica start(String name) throws Exception {
            Path directory=Files.createDirectory(root.resolve(name));directories.add(directory);
            var command=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx512m","-cp",
                    System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),ReplicaApplicationProcess.class.getName(),directory.toString());
            var env=command.environment();env.clear();
            env.putAll(Map.of("PATH",System.getenv().getOrDefault("PATH","/usr/bin:/bin"),"JAVA_HOME",System.getProperty("java.home"),
                    "SPRING_PROFILES_ACTIVE","test,replica-consistency-fixture","SERVER_PORT","0","SERVER_ADDRESS","127.0.0.1",
                    "SPRING_DATASOURCE_URL",isolated,"SPRING_DATASOURCE_USERNAME",user,"SPRING_DATASOURCE_PASSWORD",password,
                    "SPRING_FLYWAY_DEFAULT_SCHEMA",schema,"CODE_CONTEXT_API_KEYS","replica=replica-integration-token"));
            env.putAll(Map.of("CODE_CONTEXT_ADMIN_API_KEYS","admin=replica-admin-token","REPOSITORY_ROOT",root.resolve("mirrors").toString(),
                    "LOCAL_GIT_ROOT",root.resolve("sources").toString(),"MAX_INDEX_CONCURRENCY","1","INDEX_POLL_MS","100",
                    "GRAPHIFY_ENABLED","false","GENERATION_GC_POLL_MS","3600000","CONTRACT_REFRESH_POLL_MS","3600000"));
            for(var entry:Map.of("ZOEKT_INDEXER","zoekt-git-index","ZOEKT_CLEANER","zoekt-local-sync","ZOEKT_INDEX_ROOT",root.resolve("index").toString(),
                    "ZOEKT_WEB_SERVER","http://localhost:6070","OLLAMA_BASE_URL","http://localhost:11434","EMBEDDING_MODEL","qwen3-embedding:0.6b").entrySet())
                env.put(entry.getKey(),System.getenv().getOrDefault(entry.getKey(),entry.getValue()));
            Process process=command.redirectErrorStream(true).redirectOutput(directory.resolve("application.log").toFile()).start();processes.add(process);
            await(()->{
                assertTrue(process.isAlive(),"application exited; inspect "+directory.resolve("application.log"));
                return Files.exists(directory.resolve("ready"));
            },60,"application did not start; inspect "+directory.resolve("application.log"));
            return new Replica(process,directory,Integer.parseInt(Files.readString(directory.resolve("ready"))));
        }
        UUID enqueue(String sha) {
            return jdbc.queryForObject("INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state) VALUES(?,?,?,'QUEUED') RETURNING id",UUID.class,repository,sha,"replica-app-"+UUID.randomUUID());
        }
        void awaitJob(UUID job) throws Exception {
            await(()->{
                var row=jdbc.queryForMap("SELECT state,error FROM index_jobs WHERE id=?",job);
                assertNotEquals("FAILED",row.get("state"),"fixture indexing failed: "+row);
                return "SUCCEEDED".equals(row.get("state"));
            },90,"full application did not complete job "+job);
        }
        boolean zoektContains(UUID generation,String marker) throws Exception {
            var request=HttpRequest.newBuilder(URI.create(System.getenv().getOrDefault("ZOEKT_WEB_SERVER","http://localhost:6070")+"/api/search"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(Map.of("Q",marker+" repo:^"+REPOSITORY+"@"+generation+"$","Opts",Map.of("MaxDocDisplayCount",10))))).build();
            var response=HttpClient.newHttpClient().send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,response.statusCode());
            var result=JSON.readTree(response.body()).path("Result");assertTrue(result.isObject(),response.body());
            return result.path("Files").isArray()&&!result.path("Files").isEmpty();
        }
        @Override public void close() throws Exception {
            for(Process process:processes)process.destroy();
            try {
                for(Process process:processes)if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS));}
            } finally {
                Path logs=Files.createDirectories(Path.of("target","replica-process-logs",schema));
                for(Path directory:directories)if(Files.exists(directory.resolve("application.log")))Files.copy(directory.resolve("application.log"),logs.resolve(directory.getFileName()+".log"),StandardCopyOption.REPLACE_EXISTING);
                try {
                    if(repository!=null) {
                        var properties=new CodeContextProperties(root.resolve("mirrors").toString(),root.resolve("sources").toString(),System.getenv().getOrDefault("ZOEKT_INDEX_ROOT",root.resolve("index").toString()),"","","","","github.com","http://localhost",1024,1048576,6000,1,300,
                                new CodeContextProperties.Zoekt(System.getenv().getOrDefault("ZOEKT_INDEXER","zoekt-git-index"),System.getenv().getOrDefault("ZOEKT_CLEANER","zoekt-local-sync"),System.getenv().getOrDefault("ZOEKT_WEB_SERVER","http://localhost:6070")),new CodeContextProperties.Graphify("graphify",false));
                        var indexer=new ZoektIndexer(properties,JSON);
                        var record=new RepositoryStore(jdbc).find("fixture","replica-application").orElseThrow();
                        for(UUID generation:jdbc.queryForList("SELECT id FROM index_generations WHERE repository_id=?",UUID.class,repository))indexer.removeGeneration(record,generation);
                        indexer.removeRepositoryMetadata(repository);
                    }
                } finally {if(schemaCreated)admin.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}
            }
        }
    }
    private static final class Session {
        final HttpClient client=HttpClient.newHttpClient();final URI endpoint;String session,protocol;int sequence=1;
        Session(int port){endpoint=URI.create("http://127.0.0.1:"+port+"/mcp");}
        static Session connect(int port) throws Exception {
            var connection=new Session(port);
            var initialized=connection.post(Map.of("jsonrpc","2.0","id",connection.sequence++,"method","initialize","params",Map.of("protocolVersion","2025-03-26","capabilities",Map.of(),"clientInfo",Map.of("name","replica-application-it","version","1"))));
            connection.protocol=initialized.path("result").path("protocolVersion").asText();assertFalse(connection.protocol.isBlank());assertNotNull(connection.session);
            connection.post(Map.of("jsonrpc","2.0","method","notifications/initialized"));
            var listed=connection.post(Map.of("jsonrpc","2.0","id",connection.sequence++,"method","tools/list"));
            assertEquals(6,listed.path("result").path("tools").size(),listed.toString());
            var names=new HashSet<String>();listed.path("result").path("tools").forEach(tool->names.add(tool.path("name").asText()));
            assertEquals(Set.of("search_code","read_file","find_symbol","get_neighbors","semantic_search","impact_analysis"),names);
            return connection;
        }
        void read(String commit,String marker) throws Exception {
            var result=tool("read_file",Map.of("repository",REPOSITORY,"path",SOURCE,"startLine",1,"endLine",1));
            assertEquals(commit,result.path("commit").asText());assertTrue(result.path("content").asText().contains(marker),result.toString());
        }
        JsonNode tool(String name,Map<String,Object> arguments) throws Exception {
            var response=post(Map.of("jsonrpc","2.0","id",sequence++,"method","tools/call","params",Map.of("name",name,"arguments",arguments)));
            assertFalse(response.path("result").path("isError").asBoolean(false),response.toString());
            var structured=response.path("result").path("structuredContent");
            if(structured.isObject()||structured.isArray())return structured;
            return JSON.readTree(response.path("result").path("content").path(0).path("text").asText());
        }
        JsonNode post(Map<String,Object> body) throws Exception {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20)).header("Content-Type","application/json")
                    .header("Accept","application/json, text/event-stream").header("X-API-Key","replica-integration-token")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            if(session!=null)request.header("Mcp-Session-Id",session);
            if(protocol!=null)request.header("Mcp-Protocol-Version",protocol);
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            assertTrue(response.statusCode()>=200&&response.statusCode()<300,"MCP HTTP "+response.statusCode()+": "+response.body());
            UUID.fromString(response.headers().firstValue("X-Request-ID").orElseThrow());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value->session=value);
            String payload=response.body().lines().filter(line->line.startsWith("data:")).map(line->line.substring(5).strip()).reduce((first,last)->last).orElse(response.body());
            return payload.isBlank()?JSON.createObjectNode():JSON.readTree(payload);
        }
    }
}
