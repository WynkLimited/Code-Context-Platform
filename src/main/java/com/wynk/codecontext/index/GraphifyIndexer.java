package com.wynk.codecontext.index;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.core.JsonToken;
import com.wynk.codecontext.config.CodeContextProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class GraphifyIndexer {
    private static final long MAX_GRAPH_BYTES=512L*1024*1024;
    private static final int MAX_GRAPH_NODES=2_000_000;
    private static final int MAX_GRAPH_LINKS=5_000_000;
    private static final int MAX_ERROR_LOG_CHARS=8_192;
    private final CodeContextProperties config; private final JsonMapper json; private final ObjectReader graphNodeReader; private final JdbcTemplate jdbc;
    private final GraphifyWorkspaceStore workspaces;
    public GraphifyIndexer(CodeContextProperties c,JsonMapper m,JdbcTemplate j){config=c;json=m;graphNodeReader=m.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);jdbc=j;workspaces=new GraphifyWorkspaceStore(c,m);}
    public boolean buildAndPersist(com.wynk.codecontext.model.RepositoryRecord repository,UUID generation,String commit,long revision,UUID previous,
            List<com.wynk.codecontext.repo.GitRepositoryManager.SourceFile> files)throws Exception {
        if(!config.graphify().enabled())return false;
        GraphifyWorkspaceStore.Baseline baseline=null;
        if(previous!=null) {
            var rows=jdbc.query("SELECT id,commit_sha FROM index_generations WHERE id=? AND repository_id=? AND repository_revision=? AND published",
                    (rs,n)->new GraphifyWorkspaceStore.Baseline(rs.getObject(1,UUID.class),rs.getString(2)),previous,repository.id(),revision);
            if(!rows.isEmpty())baseline=rows.getFirst();
        }
        try {
            var workspace=workspaces.prepare(repository.id(),generation,commit,revision,baseline,files);
            run(workspace.sources(),workspace.output(),workspace.home());
            importGraph(workspace.output().resolve("graph.json"),generation);workspaces.complete(workspace);
            var provenance=new java.util.LinkedHashMap<String,Object>();
            provenance.put("mode",workspace.mode());provenance.put("baselineGeneration",baseline==null?null:baseline.generation());
            provenance.put("sourceFilesReused",workspace.reusedSources());provenance.put("sourceFilesWritten",workspace.writtenSources());
            provenance.put("changes",workspace.delta());provenance.put("relationshipProcessing","GLOBAL_RELINK_FROM_CACHED_AST");
            try(var lines=Files.lines(workspace.output().resolve("process.log"))) {
                var matcher=java.util.regex.Pattern.compile("AST cache: (\\d+)/(\\d+) files served from cache.*; (\\d+) to extract");
                for(var line:lines.limit(100000).toList()) {var match=matcher.matcher(line);if(match.find()) {
                    provenance.put("reportedCachedFiles",Integer.parseInt(match.group(1)));provenance.put("reportedScannedFiles",Integer.parseInt(match.group(2)));
                    provenance.put("reportedExtractedFiles",Integer.parseInt(match.group(3)));break;
                }}
            }
            jdbc.update("UPDATE index_generations SET graphify_provenance=?::jsonb WHERE id=?",json.writeValueAsString(provenance),generation);
            return true;
        }catch(Exception failure){try{workspaces.remove(repository.id(),generation);}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    public boolean buildAndPersist(Path repo,UUID generation) throws Exception {
        if(!config.graphify().enabled())return false;
        Path home=Files.createTempDirectory("graphify-runtime-");
        try {run(repo,repo.resolve("graphify-out"),home);importGraph(repo.resolve("graphify-out/graph.json"),generation);return true;}
        finally {try(var paths=Files.walk(home)){for(Path path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private void run(Path repo,Path output,Path home)throws Exception {
        Path graph=output.resolve("graph.json");List<String> cmd=List.of(config.graphify().command(),"update",repo.toString(),"--no-cluster");
        Files.createDirectories(output);Path log=output.resolve("process.log");
        ProcessBuilder builder=new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        com.wynk.codecontext.security.SubprocessHardening.scrubEnvironment(builder.environment());
        var child=builder.environment();child.clear();child.put("PATH","/opt/graphify/bin:/usr/local/bin:/usr/bin:/bin");child.put("HOME",home.toString());
        child.put("GRAPHIFY_OUT",output.toAbsolutePath().toString());child.put("PYTHONNOUSERSITE","1");child.put("PYTHONSAFEPATH","1");child.put("PYTHONHASHSEED","0");
        child.put("OLLAMA_HOST",System.getenv().getOrDefault("OLLAMA_BASE_URL","http://ollama:11434"));child.put("OLLAMA_BASE_URL",System.getenv().getOrDefault("OLLAMA_BASE_URL","http://ollama:11434"));child.put("GRAPHIFY_BACKEND","ollama");
        child.put("OLLAMA_MODEL",System.getenv().getOrDefault("GRAPHIFY_OLLAMA_MODEL","qwen3:0.6b"));
        Process process=builder.start();
        try {
            if(!process.waitFor(Duration.ofMinutes(30).toMillis(),TimeUnit.MILLISECONDS))throw new IOException("Graphify exceeded 30 minute limit");
            if(process.exitValue()!=0)throw new IOException("Graphify failed: "+(Files.exists(log)?readLogPrefix(log):"no output"));
        }finally {
            if(process.isAlive()){process.descendants().forEach(ProcessHandle::destroyForcibly);process.destroyForcibly();}
        }
        if(!Files.isRegularFile(graph))throw new IOException("Graphify completed without graphify-out/graph.json");
        if(Files.size(graph)>MAX_GRAPH_BYTES)throw new IOException("Graphify JSON exceeded the 512 MiB limit");
        // Import only after the child has completed; publication remains the caller's fenced transaction.
    }
    private static String readLogPrefix(Path log)throws IOException{
        try(var reader=Files.newBufferedReader(log)){
            char[] buffer=new char[MAX_ERROR_LOG_CHARS];int read=reader.read(buffer);
            return read<=0?"no output":new String(buffer,0,read);
        }
    }
    private void importGraph(Path graph,UUID generation)throws Exception {
        Map<String,Long> ids=new HashMap<>();
        boolean nodeArray=false,linkArray=false;int nodeCount=0,linkCount=0;
        try(var parser=json.tokenStreamFactory().createParser(graph.toFile())){
            while(parser.nextToken()!=null){if(parser.currentToken()!=JsonToken.PROPERTY_NAME)continue;String field=parser.currentName();
                if(field.equals("nodes")){nodeArray=parser.nextToken()==JsonToken.START_ARRAY;if(!nodeArray)continue;
                    while(parser.nextToken()!=JsonToken.END_ARRAY){if(++nodeCount>MAX_GRAPH_NODES)throw new IOException("Graphify graph exceeded the 2,000,000 node limit");JsonNode node=graphNodeReader.readTree(parser);String id=node.path("id").asText("");String file=node.path("source_file").asText("");if(id.isBlank()||file.isBlank())continue;
                        String name=first(node,"name","label","symbol","id");String kind=first(node,"type","kind","node_type","file_type");
                        int[] location=sourceLocation(node);int start=node.path("start_line").asInt(location[0]),end=node.path("end_line").asInt(location[1]);
                        long dbId=jdbc.queryForObject("INSERT INTO code_nodes(generation_id,node_type,symbol,qualified_name,file_path,start_line,end_line,language,metadata) VALUES(?,?,?,?,?,?,?,?,?::jsonb) RETURNING id",Long.class,generation,kind,name,name,file,Math.max(1,start),Math.max(start,end),node.path("language").asText(null),json.writeValueAsString(node));ids.put(id,dbId);
                    }
                } else if(field.equals("links")){linkArray=parser.nextToken()==JsonToken.START_ARRAY;if(!linkArray)continue;
                    while(parser.nextToken()!=JsonToken.END_ARRAY){if(++linkCount>MAX_GRAPH_LINKS)throw new IOException("Graphify graph exceeded the 5,000,000 link limit");JsonNode link=graphNodeReader.readTree(parser);Long from=ids.get(link.path("source").asText()),to=ids.get(link.path("target").asText());if(from==null||to==null)continue;
                        String relation=link.path("relation").asText("references");double confidence=Math.max(0,Math.min(1,link.path("confidence").asDouble(0.8)));
                        jdbc.update("INSERT INTO code_edges(generation_id,source_node,target_node,edge_type,evidence,confidence) VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING",generation,from,to,relation,json.writeValueAsString(link),confidence);
                    }
                }
            }
        }
        if(!nodeArray||!linkArray)throw new IOException("Graphify graph is missing nodes or links arrays");
    }
    private static String first(JsonNode n,String... keys){for(String k:keys)if(n.hasNonNull(k)&&!n.path(k).asText().isBlank())return n.path(k).asText();return "unknown";}
    private static int[] sourceLocation(JsonNode node){
        String value=node.path("source_location").asText("");
        var matcher=java.util.regex.Pattern.compile("L(\\d+)(?:-L?(\\d+))?").matcher(value);
        if(!matcher.find())return new int[]{1,1};
        int start=Integer.parseInt(matcher.group(1));int end=matcher.group(2)==null?start:Integer.parseInt(matcher.group(2));
        return new int[]{Math.max(1,start),Math.max(start,end)};
    }
}
