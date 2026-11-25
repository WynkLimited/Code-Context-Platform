package com.wynk.codecontext.mcp;

import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.repo.GitRepositoryManager;
import com.wynk.codecontext.repo.RepositoryStore;
import com.wynk.codecontext.search.ZoektSearchService;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore.StoredIndex;
import com.wynk.codecontext.port.contract.compat.ContractCompatibilityEngine;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChange;
import com.wynk.codecontext.port.contract.graph.CrossRepoImpactResolver;
import com.wynk.codecontext.port.contract.graph.CrossRepoImpactResolver.AnalysisStatus;
import com.wynk.codecontext.port.contract.graph.MavenConsumerBridge;
import com.wynk.codecontext.port.contract.source.JavaCallSiteAnalyzer;
import com.wynk.codecontext.index.ConsumerContractIndexRefresher;
import com.wynk.codecontext.ops.PlatformMetrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

@Service
public class CodeContextTools {
    private final ZoektSearchService search; private final RepositoryStore repos; private final JdbcTemplate jdbc; private final EmbeddingModel embeddings; private final GitRepositoryManager git; private final CodeContextProperties props;
    private final PostgresContractIndexStore contractIndexes; private final ContractCompatibilityEngine compatibility; private final CrossRepoImpactResolver impactResolver; private final JsonMapper objectMapper; private final ConsumerContractIndexRefresher contractRefresher;
    private final PlatformMetrics metrics;
    public CodeContextTools(ZoektSearchService s,RepositoryStore r,JdbcTemplate j,EmbeddingModel e,GitRepositoryManager g,CodeContextProperties p,PostgresContractIndexStore contractIndexes,ContractCompatibilityEngine compatibility,CrossRepoImpactResolver impactResolver,JsonMapper objectMapper,ConsumerContractIndexRefresher contractRefresher){
        this(s,r,j,e,g,p,contractIndexes,compatibility,impactResolver,objectMapper,contractRefresher,new PlatformMetrics(new SimpleMeterRegistry()));
    }
    @Autowired
    public CodeContextTools(ZoektSearchService s,RepositoryStore r,JdbcTemplate j,EmbeddingModel e,GitRepositoryManager g,CodeContextProperties p,PostgresContractIndexStore contractIndexes,ContractCompatibilityEngine compatibility,CrossRepoImpactResolver impactResolver,JsonMapper objectMapper,ConsumerContractIndexRefresher contractRefresher,PlatformMetrics metrics){search=s;repos=r;jdbc=j;embeddings=e;git=g;props=p;this.contractIndexes=contractIndexes;this.compatibility=compatibility;this.impactResolver=impactResolver;this.objectMapper=objectMapper;this.contractRefresher=contractRefresher;this.metrics=metrics;}
    private String team(){return String.valueOf(SecurityContextHolder.getContext().getAuthentication().getPrincipal());}
    private RepositoryRecord allowed(String key){String[] p=key.split("/",2);if(p.length!=2)throw new IllegalArgumentException("repository must be owner/name");return repos.find(p[0],p[1]).filter(r->repos.findAccessible(team()).stream().anyMatch(x->x.id().equals(r.id()))).orElseThrow(()->new SecurityException("repository not found or access denied"));}

    @Tool(description="Search exact text or regular expressions across repositories the authenticated team may access.")
    public String search_code(String query){
        Timer.Sample sample=metrics.startMcp();
        boolean error=false;
        try { return search.search(query); }
        catch(RuntimeException e){ error=true; throw e; }
        finally { metrics.recordMcp(sample,error); }
    }

    @Tool(description="Read a UTF-8 source file from a Git repository, optionally limited to a one-based line range.")
    public String read_file(String repository,String path,Integer startLine,Integer endLine) throws Exception {
        Timer.Sample sample=metrics.startMcp();
        boolean error=false;
        try {
        RepositoryRecord repo=allowed(repository); if(path==null||path.isBlank()||Path.of(path).isAbsolute())throw new IllegalArgumentException("relative source path required");
        String commit=repos.currentCommit(repo.id()).orElseThrow(()->new IllegalStateException("repository has no published generation"));
        String content=git.readFileAtRevision(Path.of(repo.mirrorPath()),commit,path,1_048_576);List<String> lines=content.lines().toList();int start=startLine==null?1:Math.max(1,startLine);int end=endLine==null?lines.size():Math.min(lines.size(),endLine);
        if(start>end||end-start>2000)throw new IllegalArgumentException("invalid line range");
        StringBuilder out=new StringBuilder();for(int i=start;i<=end;i++)out.append(i).append(": ").append(lines.get(i-1)).append('\n');
        return objectMapper.writeValueAsString(java.util.Map.of("repository",repo.key(),"commit",commit,"path",path,"startLine",start,"endLine",end,"content",out.toString()));
        } catch(Exception e){ error=true; throw e; }
        finally { metrics.recordMcp(sample,error); }
    }

    @Tool(description="Find class, method, or symbol definitions with repository and exact line locations.")
    public String find_symbol(String symbol,String repository){
        Timer.Sample sample=metrics.startMcp();
        boolean error=false;
        try {
        if(symbol==null||symbol.isBlank())throw new IllegalArgumentException("symbol is required");
        String like="%"+symbol+"%";
        var args=new java.util.ArrayList<Object>();
        args.add(team()); args.add(like); args.add(like);
        String sql="""
SELECT r.owner||'/'||r.name AS repository,g.commit_sha AS commit,n.node_type,n.symbol,n.qualified_name,n.file_path,n.start_line,n.end_line
          FROM code_nodes n JOIN index_generations g ON g.id=n.generation_id AND g.published=true
          JOIN repositories r ON r.id=g.repository_id JOIN team_repository_access a ON a.repository_id=r.id AND a.team=?
          WHERE (n.symbol ILIKE ? OR coalesce(n.qualified_name,'') ILIKE ?)""";
        if(repository!=null&&!repository.isBlank()){sql+=" AND r.owner||'/'||r.name=?";args.add(repository);}
        sql+=" ORDER BY 1,n.start_line LIMIT 100";
        return objectMapper.writeValueAsString(jdbc.queryForList(sql,args.toArray()));
        } catch(RuntimeException e){ error=true; throw e; }
        finally { metrics.recordMcp(sample,error); }
    }

    @Tool(description="Return direct callers and callees of a symbol, including evidence and confidence.")
    public String get_neighbors(String repository,String symbol){
        Timer.Sample sample=metrics.startMcp();
        boolean error=false;
        try {
        if(symbol==null||symbol.isBlank())throw new IllegalArgumentException("symbol is required");
        RepositoryRecord r=allowed(repository);
        String like="%"+symbol+"%";
        var rows=jdbc.queryForList("""
SELECT r.owner||'/'||r.name AS repository,g.commit_sha AS commit,source.symbol AS from_symbol,target.symbol AS to_symbol,e.edge_type,e.evidence,e.confidence,source.file_path,source.start_line
          FROM code_edges e JOIN code_nodes source ON source.id=e.source_node LEFT JOIN code_nodes target ON target.id=e.target_node
          JOIN index_generations g ON g.id=e.generation_id AND g.published=true JOIN repositories r ON r.id=g.repository_id WHERE g.repository_id=? AND (coalesce(source.qualified_name,'') ILIKE ? OR coalesce(target.qualified_name,'') ILIKE ?)
        UNION ALL
        SELECT r.owner||'/'||r.name AS repository,g.commit_sha AS commit,caller.symbol_key AS from_symbol,target.symbol_key AS to_symbol,'SEMANTIC_'||edge.resolution_status AS edge_type,
               edge.evidence,edge.confidence,site.file_path,site.start_line
          FROM semantic_call_edges edge
          JOIN semantic_symbols caller ON caller.id=edge.caller_symbol_id
          LEFT JOIN semantic_symbols target ON target.id=edge.target_symbol_id
          JOIN semantic_call_sites site ON site.id=edge.call_site_id
          JOIN index_generations g ON g.id=edge.generation_id AND g.published=true JOIN repositories r ON r.id=g.repository_id
         WHERE g.repository_id=? AND (caller.symbol_key ILIKE ? OR coalesce(target.symbol_key,'') ILIKE ?)
        LIMIT 200""",r.id(),like,like,r.id(),like,like);
        return objectMapper.writeValueAsString(rows);
        } catch(RuntimeException e){ error=true; throw e; }
        finally { metrics.recordMcp(sample,error); }
    }

    @Tool(description="Find code chunks by natural language using the self-hosted embedding model and pgvector cosine distance.")
    public String semantic_search(String query,String repository,Integer limit){
        Timer.Sample sample=metrics.startMcp();
        boolean error=false;
        try {
        if(query==null||query.isBlank())throw new IllegalArgumentException("semantic search query is required");
        if(query.length()>1024)throw new IllegalArgumentException("semantic search query exceeds 1024 characters");
        String owner=null,name=null;if(repository!=null&&!repository.isBlank()){RepositoryRecord r=allowed(repository);owner=r.owner();name=r.name();}
        float[] v=embeddings.embed(query);if(v.length!=props.embeddingDimensions())throw new IllegalStateException("embedding model dimension mismatch");
        for(float value:v)if(!Float.isFinite(value))throw new IllegalStateException("embedding model returned a non-finite vector");
        String vec="["+String.join(",",java.util.stream.IntStream.range(0,v.length).mapToObj(i->Float.toString(v[i])).toList())+"]";
        int n=limit==null?10:Math.max(1,Math.min(limit,50));
        String sql="""
SELECT r.owner||'/'||r.name AS repository,g.commit_sha AS commit,c.file_path,c.symbol,c.start_line,c.end_line,c.content,
            1-(c.embedding <=> ?::vector) AS similarity FROM code_chunks c JOIN index_generations g ON g.id=c.generation_id AND g.published
            JOIN repositories r ON r.id=g.repository_id JOIN team_repository_access a ON a.repository_id=r.id AND a.team=?
            WHERE c.embedding IS NOT NULL""";
        if(owner!=null)sql+=" AND r.owner=? AND r.name=?";
        sql+=" ORDER BY c.embedding <=> ?::vector LIMIT "+n;
        List<?> rows=owner==null?jdbc.queryForList(sql,vec,team(),vec):jdbc.queryForList(sql,vec,team(),owner,name,vec);
        return objectMapper.writeValueAsString(rows);
        } catch(RuntimeException e){ error=true; throw e; }
        finally { metrics.recordMcp(sample,error); }
    }


    @Tool(description="Compare published contract generations, classify supported changes, and return fresh, evidence-backed candidate consumers with completeness status. Optional oldSignature/newSignature classify a proposed method change without claiming definite breakage. Method-level consumer behavior is not inferred; results never block a review.")
    public java.util.Map<String,Object> impact_analysis(
            String contract,
            String repository,
            @ToolParam(required=false, description="Existing method/API signature for proposed-change classification") String oldSignature,
            @ToolParam(required=false, description="Proposed method/API signature for proposed-change classification") String newSignature){
        Timer.Sample sample=metrics.startMcp();
        boolean error=false;
        try { return computeImpactAnalysis(contract,repository,oldSignature,newSignature); }
        catch(SecurityException e) { error=true; throw e; }
        catch(Exception e) { error=true; return java.util.Map.of("status","FAILED","blocking",false,"reason","Impact analysis could not be completed; consult platform logs.","results",List.of()); }
        finally { metrics.recordMcp(sample,error); }
    }

    private java.util.Map<String,Object> computeImpactAnalysis(String contract,String repository){
        return computeImpactAnalysis(contract,repository,null,null);
    }

    private java.util.Map<String,Object> computeImpactAnalysis(String contract,String repository,String oldSignature,String newSignature){
        RepositoryRecord r=allowed(repository);
        StoredIndex after=contractIndexes.published(r.id()).orElse(null);
        if(after==null) return java.util.Map.of("status","INCOMPLETE","blocking",false,"reason","No published contract index is available for the requested repository.","results",List.of());
        List<String> latestRequested=jdbc.query("SELECT commit_sha FROM index_jobs WHERE repository_id=? ORDER BY created_at DESC LIMIT 1",(rs,n)->rs.getString(1),r.id());
        boolean pending=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM index_jobs WHERE repository_id=? AND state IN ('QUEUED','RUNNING'))",Boolean.class,r.id()));
        if(pending||!latestRequested.isEmpty()&&!latestRequested.get(0).equals(after.index().commit())) {
            var stale=java.util.Map.of("status","STALE","blocking",false,"repository",r.key(),"indexedCommit",after.index().commit(),"requestedCommit",latestRequested.isEmpty()?"":latestRequested.get(0),"reason","The latest requested repository commit is not fully indexed; impact analysis is incomplete.","results",List.of());
            persistImpact(r,after,null,"STALE",stale);
            return stale;
        }
        StoredIndex before=contractIndexes.previousSuccessful(r.id(),after.generationNumber()).orElse(null);
        var beforeFreshness=before==null?com.wynk.codecontext.port.contract.ContractModels.IndexFreshness.MISSING:contractIndexes.freshness(before);
        var afterFreshness=contractIndexes.freshness(after);
        if((before!=null&&beforeFreshness!=com.wynk.codecontext.port.contract.ContractModels.IndexFreshness.CURRENT)||afterFreshness!=com.wynk.codecontext.port.contract.ContractModels.IndexFreshness.CURRENT) {
            var stale=java.util.Map.of("status","STALE","blocking",false,"repository",r.key(),"beforeCommit",before==null?"":before.index().commit(),"afterCommit",after.index().commit(),"beforeGeneration",before==null?"":before.generationId(),"afterGeneration",after.generationId(),"reason","One or both contract generations were produced by stale, incomplete, or invalid extractor metadata.","results",List.of());
            persistImpact(r,after,before,"STALE",stale);
            return stale;
        }
        List<ContractChange> allChanges=compatibility.compare(before==null?List.of():before.index().exports(),after.index().exports());
        String needle=contract==null?"":contract.strip().toLowerCase(java.util.Locale.ROOT);
        List<ContractChange> changes=needle.isEmpty()?allChanges:allChanges.stream().filter(c->c.contractKey().toLowerCase(java.util.Locale.ROOT).contains(needle)||c.identity().qualifiedName()!=null&&c.identity().qualifiedName().toLowerCase(java.util.Locale.ROOT).contains(needle)).toList();
        if(changes.isEmpty()) {
            var noChange=java.util.Map.of("status","INCOMPLETE","blocking",false,"repository",r.key(),"beforeCommit",before==null?"":before.index().commit(),"afterCommit",after.index().commit(),"beforeGeneration",before==null?"":before.generationId(),"afterGeneration",after.generationId(),"reason",needle.isEmpty()?"No contract changes were detected between available generations; an empty change set is not a safety verdict.":"No detected contract change matches the requested filter; this is not a safety verdict.","results",List.of());
            persistImpact(r,after,before,"INCOMPLETE",noChange);
            return noChange;
        }
        var routingKeys=new java.util.LinkedHashSet<String>();
        for(ContractChange change:changes) routingKeys.addAll(MavenConsumerBridge.consumerMatchKeys(after.index(),change));
        CrossRepoImpactResolver.Resolution resolution;
        try { resolution=impactResolver.resolve(team(),r.id(),before==null?null:before.index(),after.index(),changes); }
        catch(Exception e) {
            var failed=java.util.Map.of("status","FAILED","blocking",false,"repository",r.key(),"beforeCommit",before==null?"":before.index().commit(),"afterCommit",after.index().commit(),"reason","The resolver failed; consult platform logs.","results",List.of());
            persistImpact(r,after,before,"FAILED",failed);
            return failed;
        }
        // Capture the immutable published consumer generations for this analysis before scheduling
        // refresh jobs. A newly queued refresh must not make the generation being analyzed appear
        // stale midway through the same request.
        var refreshRequest=contractRefresher.refreshRelevant(team(),r.id(),after.generationId(),routingKeys);
        boolean unknown=changes.stream().anyMatch(c->c.compatibility()==com.wynk.codecontext.port.contract.compat.CompatibilityModels.Compatibility.UNKNOWN);
        boolean breakingConsumerCandidate=changes.stream().anyMatch(ContractChange::isBreaking)&&resolution.impacted().stream().anyMatch(i->"DIRECT".equals(i.relationship()));
        var methodEvidence = collectMethodEvidence(resolution.impacted(), changes);
        String status=before==null?"INCOMPLETE":(unknown||contractIndexes.hasCoverageGaps(after)||contractIndexes.hasCoverageGaps(before)||refreshRequest.scheduled()>0||breakingConsumerCandidate||!methodEvidence.complete()||!resolution.semanticMethodImpacts().isEmpty())&&resolution.status()==AnalysisStatus.COMPLETE?"PARTIAL":resolution.status().name();
        var result=new java.util.LinkedHashMap<String,Object>();
        result.put("status",status); result.put("blocking",false); result.put("repository",r.key());
        result.put("beforeCommit",before==null?null:before.index().commit()); result.put("afterCommit",after.index().commit());
        result.put("beforeGeneration",before==null?null:before.generationId()); result.put("afterGeneration",after.generationId());
        result.put("compatibilityRulesVersion",compatibility.rulesVersion()); result.put("candidateCount",resolution.candidateCount());
        result.put("truncated",resolution.truncated()); result.put("changes",changes); result.put("impactCandidates",resolution.impacted());
        result.put("methodLevelEvidence", methodEvidence.evidence());
        result.put("methodLevelAnalysis", methodEvidence.complete() ? "COMPLETE_FOR_SUPPORTED_JAVA_FILES" : "INCOMPLETE");
        result.put("methodLevelAnalysisComplete", methodEvidence.complete());
        result.put("semanticMethodImpacts",resolution.semanticMethodImpacts());
        result.put("skippedRepositories",resolution.skipped()); result.put("contractGraph",resolution.graph());
        result.put("unknownCompatibilityPresent",unknown);
        result.put("confirmedConsumerBreakage",false); result.put("consumerBehaviorAnalysis","NOT_RUN");
        result.put("safetyConclusion","NO_AUTOMATIC_SAFETY_CONCLUSION");
        if(oldSignature!=null&&!oldSignature.isBlank()||newSignature!=null&&!newSignature.isBlank()) {
            var proposed=com.wynk.codecontext.port.contract.graph.ProposedChangeClassifier.classify(oldSignature,newSignature);
            result.put("proposedChange",java.util.Map.of(
                    "oldSignature",oldSignature==null?"":oldSignature,
                    "newSignature",newSignature==null?"":newSignature,
                    "kind",proposed.kind().name(),
                    "impactClass",proposed.impactClass(),
                    "explanation",proposed.explanation(),
                    "confirmedBreakage",false));
        }
        result.put("completenessScope","Contract generation comparison, team-scoped Maven dependency routing, exact V5 Java semantic method references, and Kotlin compiler JVM call edges where dependency artifacts and provider symbols are available; Kotlin source compatibility stays UNKNOWN; results remain informational.");
        result.put("consumerRefreshScheduled",refreshRequest.scheduled()); result.put("consumerRefreshTruncated",refreshRequest.truncated());
        result.put("limitations",List.of("Results are informational and do not block reviews.","Removed provider signatures matching a consumer's resolved V5 edge are reported as potential upgrade impact, not as current breakage.","Kotlin JVM binary edges from the compiler analyzer are reported when present; Kotlin source compatibility (inline, suspend, defaults, nullability, exact source mappings) remains UNKNOWN and never proves safety.","Full indirect method-chain resolution remains unsupported.","Repositories outside the authenticated team's access scope are not searched.","Proposed-change classification distinguishes potential binary incompatibility from behavioural unknowns; it never claims definite runtime breakage."));
        persistImpact(r,after,before,status,result);
        return result;
    }

    private MethodEvidence collectMethodEvidence(List<CrossRepoImpactResolver.Impacted> impacted, List<ContractChange> changes) {
        var rows = new java.util.ArrayList<java.util.Map<String,Object>>(); boolean complete = true;
        for (var candidate : impacted) {
            RepositoryRecord consumer = repos.find(candidate.repo().owner(), candidate.repo().name()).orElse(null);
            if (consumer == null || !repos.findAccessible(team()).stream().anyMatch(x -> x.id().equals(consumer.id()))) { complete = false; continue; }
            for (String file : candidate.files()) {
                if (file == null || !(file.endsWith(".java"))) { complete = false; continue; }
                try {
                    String source = git.readFileAtRevision(Path.of(consumer.mirrorPath()), candidate.commit(), file, 1_048_576);
                    for (ContractChange change : changes) {
                        if (!candidate.contractKeys().contains(change.contractKey())) continue;
                        var id = change.identity(); var names = new java.util.LinkedHashSet<String>();
                        if (id.operation() != null) names.add(id.operation());
                        if (id.name() != null && id.type() != com.wynk.codecontext.port.contract.ContractModels.ContractType.MAVEN) names.add(id.name());
                        if (names.isEmpty()) { complete = false; continue; }
                        var parsed = JavaCallSiteAnalyzer.analyze(file, source, names);
                        if (!parsed.complete()) { complete = false; continue; }
                        for (var call : parsed.callSites()) {
                            var row = new java.util.LinkedHashMap<String,Object>();
                            row.put("repository", candidate.repo().key()); row.put("commit", candidate.commit()); row.put("filePath", file);
                            row.put("class", call.className()); row.put("method", call.methodName()); row.put("startLine", call.startLine()); row.put("endLine", call.endLine());
                            row.put("source", call.source()); row.put("referencedContract", change.contractKey());
                            row.put("dependencyVersion", dependencyVersion(candidate.generationId(), file, change.contractKey()));
                            row.put("relationship", candidate.relationship()); row.put("evidenceType", "DETERMINISTIC_JAVA_SYNTAX_CALL_SITE");
                            row.put("confidence", "MEDIUM"); row.put("compatibilityClassification", change.compatibility().name());
                            row.put("analysisCompleteness", candidate.freshness().name().equals("CURRENT") ? "SOURCE_PARSED" : "STALE_INDEX");
                            row.put("impactConclusion", "POTENTIAL_IMPACT_CANDIDATE"); rows.add(row);
                        }
                    }
                } catch (Exception e) { complete = false; }
            }
        }
        return new MethodEvidence(List.copyOf(rows), complete);
    }
    private String dependencyVersion(UUID generation, String file, String contractKey) {
        List<String> versions = jdbc.query("SELECT DISTINCT dependency_version FROM contracts WHERE generation_id=? AND role='IMPORT' AND file_path=? AND canonical_key=? AND dependency_version IS NOT NULL",
                (rs, n) -> rs.getString(1), generation, file, contractKey);
        return versions.size() == 1 ? versions.get(0) : null;
    }
    private record MethodEvidence(List<java.util.Map<String,Object>> evidence, boolean complete) {}

    private void persistImpact(RepositoryRecord repo,StoredIndex after,StoredIndex before,String status,Object result) {
        try {
            String json=objectMapper.writeValueAsString(result);
            jdbc.update("INSERT INTO cross_repo_impact_analyses(team,origin_repository_id,before_generation_id,after_generation_id,before_commit,after_commit,analysis_status,result) VALUES(?,?,?,?,?,?,?,?::jsonb) ON CONFLICT(team,origin_repository_id,after_generation_id) DO UPDATE SET before_generation_id=excluded.before_generation_id,before_commit=excluded.before_commit,analysis_status=excluded.analysis_status,result=excluded.result,created_at=now()",
                    team(),repo.id(),before==null?null:before.generationId(),after.generationId(),before==null?null:before.index().commit(),after.index().commit(),status,json);
        } catch(Exception e) { throw new IllegalStateException("failed to persist cross-repository impact result",e); }
    }
}
