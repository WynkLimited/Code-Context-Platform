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
    public GraphifyIndexer(CodeContextProperties c,JsonMapper m,JdbcTemplate j){config=c;json=m;graphNodeReader=m.reader().without(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);jdbc=j;}
    public boolean buildAndPersist(Path repo,UUID generation) throws Exception {
        if(!config.graphify().enabled())return false;
        Path graph=repo.resolve("graphify-out/graph.json");List<String> cmd=List.of(config.graphify().command(),"update",repo.toString(),"--no-cluster");
        Path log=repo.resolve(".graphify-index.log");
        ProcessBuilder builder=new ProcessBuilder(cmd).directory(repo.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        com.wynk.codecontext.security.SubprocessHardening.scrubEnvironment(builder.environment());
        var child=builder.environment();child.clear();child.put("PATH","/opt/graphify/bin:/usr/local/bin:/usr/bin:/bin");child.put("HOME",repo.toString());
        child.put("OLLAMA_HOST",System.getenv().getOrDefault("OLLAMA_BASE_URL","http://ollama:11434"));child.put("OLLAMA_BASE_URL",System.getenv().getOrDefault("OLLAMA_BASE_URL","http://ollama:11434"));child.put("GRAPHIFY_BACKEND","ollama");
        child.put("OLLAMA_MODEL",System.getenv().getOrDefault("GRAPHIFY_OLLAMA_MODEL","qwen3:0.6b"));
        Process process=builder.start();boolean done=process.waitFor(Duration.ofMinutes(30).toMillis(),TimeUnit.MILLISECONDS);
        if(!done){process.destroyForcibly();throw new IOException("Graphify exceeded 30 minute limit");}
        if(process.exitValue()!=0)throw new IOException("Graphify failed: "+(Files.exists(log)?readLogPrefix(log):"no output"));
        if(!Files.isRegularFile(graph))throw new IOException("Graphify completed without graphify-out/graph.json");
        if(Files.size(graph)>MAX_GRAPH_BYTES)throw new IOException("Graphify JSON exceeded the 512 MiB limit");
        importGraph(graph,generation);return true;
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
