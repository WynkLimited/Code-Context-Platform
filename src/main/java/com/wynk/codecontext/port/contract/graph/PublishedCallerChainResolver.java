package com.wynk.codecontext.port.contract.graph;

import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static com.wynk.codecontext.port.contract.graph.CrossRepoImpactResolver.SemanticMethodImpact;

/** Bounded reverse reachability inside a directly affected consumer's exact published snapshot. */
public final class PublishedCallerChainResolver {
    private static final int MAX_DEPTH=3, MAX_RESULTS=100, MAX_QUERIES=100;
    private final EdgeSource source;
    public PublishedCallerChainResolver(JdbcTemplate jdbc) {
        this((team,root,generation,target,limit)->jdbc.query("""
            SELECT g.id,caller.symbol_key,site.file_path,site.start_line,site.end_line,edge.evidence,edge.confidence
            FROM semantic_call_edges edge
            JOIN index_generations g ON g.id=edge.generation_id AND g.repository_id=edge.repository_id AND g.published
            JOIN repositories r ON r.id=g.repository_id AND r.enabled
            JOIN team_repository_access access ON access.repository_id=r.id AND access.team=?
            JOIN semantic_call_sites site ON site.id=edge.call_site_id AND site.repository_id=r.id
              AND site.generation_id=g.id AND site.commit_sha=g.commit_sha AND site.resolution_status='RESOLVED'
            JOIN semantic_symbols caller ON caller.id=edge.caller_symbol_id AND caller.repository_id=r.id
              AND caller.generation_id=g.id AND caller.commit_sha=g.commit_sha AND caller.resolution_status='RESOLVED'
            JOIN semantic_symbols target ON target.id=edge.target_symbol_id AND target.repository_id=r.id
              AND target.generation_id=g.id AND target.commit_sha=g.commit_sha AND target.resolution_status='RESOLVED'
            WHERE lower(r.owner||'/'||r.name)=? AND g.id=? AND g.commit_sha=? AND target.symbol_key=?
              AND edge.resolution_status='RESOLVED' AND caller.symbol_key=site.caller_class||'#'||site.caller_method
            ORDER BY caller.symbol_key,site.file_path,site.start_line,edge.id LIMIT ?
            """,(rs,n)->new Edge(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4),rs.getInt(5),rs.getString(6),rs.getDouble(7)),
                team,root.consumerRepository().toLowerCase(Locale.ROOT),generation,root.consumerCommit(),target,limit));
    }
    PublishedCallerChainResolver(EdgeSource source){this.source=source;}
    @FunctionalInterface interface EdgeSource {List<Edge> callers(String team,SemanticMethodImpact root,UUID generation,String target,int limit);}
    public record Edge(UUID generation,String caller,String file,int startLine,int endLine,String evidence,double confidence) {}
    public record Chain(String providerRepository,String providerCommit,String providerSymbol,String consumerRepository,String consumerCommit,
                        UUID generationId,String directConsumerSymbol,String callerSymbol,int depth,List<String> symbols,
                        List<Edge> callSites,String dependencyArtifact,String dependencyVersion,String currentCompatibility,
                        double confidence,String classification,boolean confirmedBreakage) {}
    public record Result(List<Chain> chains,int maxDepth,int maxResults,boolean truncated,boolean depthBoundReached,
                         String scope,boolean complete) {}
    private record Pending(SemanticMethodImpact root,UUID generation,List<String> symbols,List<Edge> edges) {}

    public Result resolve(String team,List<SemanticMethodImpact> impacts,Map<String,UUID> generations) {
        if(team==null||team.isBlank())throw new SecurityException("authenticated team is required");
        var queue=new ArrayDeque<Pending>();var seenRoots=new HashSet<String>();
        boolean truncated=false,depthBound=false;
        for(var impact:impacts) {
            if(!"CURRENT".equals(impact.freshness())||!"RESOLVED".equals(impact.resolutionState())
                    ||impact.callerClass()==null||impact.callerMethod()==null)continue;
            String symbol=impact.callerClass()+"#"+impact.callerMethod();
            UUID generation=generations.get(impact.consumerRepository().toLowerCase(Locale.ROOT)+"@"+impact.consumerCommit());
            if(generation==null)continue;
            if(!seenRoots.add(impact.consumerRepository()+"|"+impact.consumerCommit()+"|"+symbol+"|"+impact.dependencyArtifact()+"|"+impact.oldSignature()))continue;
            if(queue.size()==MAX_QUERIES){truncated=true;break;}
            queue.add(new Pending(impact,generation,List.of(symbol),List.of()));
        }
        var result=new ArrayList<Chain>();var seen=new HashSet<String>();int queries=0;
        while(!queue.isEmpty()) {
            if(queries==MAX_QUERIES||result.size()==MAX_RESULTS){truncated=true;break;}
            var pending=queue.removeFirst();
            if(pending.edges.size()==MAX_DEPTH){depthBound=true;continue;}
            queries++;
            var edges=source.callers(team,pending.root,pending.generation,pending.symbols.get(0),MAX_RESULTS-result.size()+1);
            for(var edge:edges) {
                if(!pending.generation.equals(edge.generation))throw new IllegalStateException("caller chain edge belongs to a different generation");
                if(pending.symbols.contains(edge.caller))continue; // cycles do not imply new affected callers
                String key=pending.root.consumerRepository()+"|"+pending.root.consumerCommit()+"|"+pending.symbols.get(pending.symbols.size()-1)+"|"+pending.root.dependencyArtifact()+"|"+pending.root.oldSignature()+"|"+edge.caller;
                if(!seen.add(key))continue;
                if(result.size()==MAX_RESULTS){truncated=true;break;}
                var symbols=new ArrayList<String>();symbols.add(edge.caller);symbols.addAll(pending.symbols);
                var path=new ArrayList<Edge>();path.add(edge);path.addAll(pending.edges);
                double confidence=Math.min(pending.root.confidence(),path.stream().mapToDouble(Edge::confidence).min().orElse(0));
                result.add(new Chain(pending.root.providerRepository(),pending.root.providerCommit(),pending.root.providerClass()+"#"+pending.root.oldSignature(),pending.root.consumerRepository(),
                        pending.root.consumerCommit(),edge.generation,symbols.get(symbols.size()-1),edge.caller,path.size(),
                        List.copyOf(symbols),List.copyOf(path),pending.root.dependencyArtifact(),pending.root.dependencyVersion(),
                        pending.root.currentCompatibility(),confidence,"POTENTIAL_UPGRADE_CALLER",false));
                queue.addLast(new Pending(pending.root,pending.generation,List.copyOf(symbols),List.copyOf(path)));
            }
        }
        return new Result(List.copyOf(result),MAX_DEPTH,MAX_RESULTS,truncated,depthBound,
                "INTRA_REPOSITORY_PUBLISHED_RESOLVED_CALL_GRAPH",false);
    }
}
