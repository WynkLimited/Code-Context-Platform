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
import tools.jackson.databind.json.JsonMapper;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.annotation.Tool;
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
    public CodeContextTools(ZoektSearchService s,RepositoryStore r,JdbcTemplate j,EmbeddingModel e,GitRepositoryManager g,CodeContextProperties p,PostgresContractIndexStore contractIndexes,ContractCompatibilityEngine compatibility,CrossRepoImpactResolver impactResolver,JsonMapper objectMapper,ConsumerContractIndexRefresher contractRefresher){search=s;repos=r;jdbc=j;embeddings=e;git=g;props=p;this.contractIndexes=contractIndexes;this.compatibility=compatibility;this.impactResolver=impactResolver;this.objectMapper=objectMapper;this.contractRefresher=contractRefresher;}
    private String team(){return String.valueOf(SecurityContextHolder.getContext().getAuthentication().getPrincipal());}
    private RepositoryRecord allowed(String key){String[] p=key.split("/",2);if(p.length!=2)throw new IllegalArgumentException("repository must be owner/name");return repos.find(p[0],p[1]).filter(r->repos.findAccessible(team()).stream().anyMatch(x->x.id().equals(r.id()))).orElseThrow(()->new SecurityException("repository not found or access denied"));}

    @Tool(description="Search exact text or regular expressions across repositories the authenticated team may access.")
    public String search_code(String query){return search.search(query);}

    @Tool(description="Read a UTF-8 source file from a Git repository, optionally limited to a one-based line range.")
    public String read_file(String repository,String path,Integer startLine,Integer endLine) throws Exception {
        RepositoryRecord repo=allowed(repository); if(path==null||path.isBlank()||Path.of(path).isAbsolute())throw new IllegalArgumentException("relative source path required");
        String commit=repos.currentCommit(repo.id()).orElseThrow(()->new IllegalStateException("repository has no published generation"));
        String content=git.readFileAtRevision(Path.of(repo.mirrorPath()),commit,path,1_048_576);List<String> lines=content.lines().toList();int start=startLine==null?1:Math.max(1,startLine);int end=endLine==null?lines.size():Math.min(lines.size(),endLine);
        if(start>end||end-start>2000)throw new IllegalArgumentException("invalid line range");
        StringBuilder out=new StringBuilder();for(int i=start;i<=end;i++)out.append(i).append(": ").append(lines.get(i-1)).append('\n');return out.toString();
    }

    @Tool(description="Find class, method, or symbol definitions with repository and exact line locations.")
    public String find_symbol(String symbol,String repository){
        String sql="""
SELECT r.owner||'/'||r.name AS repository,n.node_type,n.symbol,n.qualified_name,n.file_path,n.start_line,n.end_line
          FROM code_nodes n JOIN index_generations g ON g.id=n.generation_id AND g.published
          JOIN repositories r ON r.id=g.repository_id JOIN team_repository_access a ON a.repository_id=r.id AND a.team=?
          WHERE (n.symbol ILIKE ? OR n.qualified_name ILIKE ?)""";
        Object[] args=new Object[]{team(),"%"+symbol+"%","%"+symbol+"%"};
        String filter=repository==null||repository.isBlank()?"":" AND r.owner||'/'||r.name=?";
        if(!filter.isEmpty()){sql+=filter;args=new Object[]{team(),"%"+symbol+"%","%"+symbol+"%",repository};}
        return jdbc.queryForList(sql+" ORDER BY repository,n.start_line LIMIT 100",args).toString();
    }

    @Tool(description="Return direct callers and callees of a symbol, including evidence and confidence.")
    public String get_neighbors(String repository,String symbol){
        RepositoryRecord r=allowed(repository);
        return jdbc.queryForList("""
SELECT source.symbol AS from_symbol,target.symbol AS to_symbol,e.edge_type,e.evidence,e.confidence,source.file_path,source.start_line
          FROM code_edges e JOIN code_nodes source ON source.id=e.source_node LEFT JOIN code_nodes target ON target.id=e.target_node
          JOIN index_generations g ON g.id=e.generation_id AND g.published WHERE g.repository_id=? AND (source.qualified_name ILIKE ? OR target.qualified_name ILIKE ?)
        UNION ALL
        SELECT caller.symbol_key AS from_symbol,target.symbol_key AS to_symbol,'SEMANTIC_'+edge.resolution_status AS edge_type,
               edge.evidence,edge.confidence,site.file_path,site.start_line
          FROM semantic_call_edges edge
          JOIN semantic_symbols caller ON caller.id=edge.caller_symbol_id
          LEFT JOIN semantic_symbols target ON target.id=edge.target_symbol_id
          JOIN semantic_call_sites site ON site.id=edge.call_site_id
          JOIN index_generations g ON g.id=edge.generation_id AND g.published
         WHERE g.repository_id=? AND (caller.symbol_key ILIKE ? OR target.symbol_key ILIKE ?)
         LIMIT 200""",r.id(),"%"+symbol+"%","%"+symbol+"%",r.id(),"%"+symbol+"%","%"+symbol+"%").toString();
    }

    @Tool(description="Find code chunks by natural language using the self-hosted embedding model and pgvector cosine distance.")
    public String semantic_search(String query,String repository,Integer limit){
        float[] v=embeddings.embed(query);if(v.length!=props.embeddingDimensions())throw new IllegalStateException("embedding model dimension mismatch");String vec="["+String.join(",",java.util.stream.IntStream.range(0,v.length).mapToObj(i->Float.toString(v[i])).toList())+"]";
        String owner=null,name=null;if(repository!=null&&!repository.isBlank()){RepositoryRecord r=allowed(repository);owner=r.owner();name=r.name();}
        int n=limit==null?10:Math.max(1,Math.min(limit,50));
        String sql="""
SELECT r.owner||'/'||r.name AS repository,c.file_path,c.symbol,c.start_line,c.end_line,c.content,
            1-(c.embedding <=> ?::vector) AS similarity FROM code_chunks c JOIN index_generations g ON g.id=c.generation_id AND g.published
            JOIN repositories r ON r.id=g.repository_id JOIN team_repository_access a ON a.repository_id=r.id AND a.team=?
            WHERE c.embedding IS NOT NULL""";
        if(owner!=null)sql+=" AND r.owner=? AND r.name=?";
        sql+=" ORDER BY c.embedding <=> ?::vector LIMIT "+n;
        List<?> rows=owner==null?jdbc.queryForList(sql,vec,team(),vec):jdbc.queryForList(sql,vec,team(),owner,name,vec);
        return rows.toString();
    }

    @Tool(description="Compare published contract generations, classify supported changes, and return fresh, evidence-backed candidate consumers with completeness status. Method-level consumer behavior is not inferred; results never block a review.")
    public java.util.Map<String,Object> impact_analysis(String contract,String repository){
        try { return computeImpactAnalysis(contract,repository); }
        catch(SecurityException e) { throw e; }
        catch(Exception e) { return java.util.Map.of("status","FAILED","blocking",false,"reason","Impact analysis could not be completed; consult platform logs.","results",List.of()); }
    }

    private java.util.Map<String,Object> computeImpactAnalysis(String contract,String repository){
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
        var refreshRequest=contractRefresher.refreshRelevant(team(),r.id(),routingKeys);
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
        result.put("completenessScope","Contract generation comparison, team-scoped Maven dependency routing, and exact V5 semantic method references where dependency artifacts and provider symbols are available; results remain informational.");
        result.put("consumerRefreshScheduled",refreshRequest.scheduled()); result.put("consumerRefreshTruncated",refreshRequest.truncated());
        result.put("limitations",List.of("Results are informational and do not block reviews.","Removed provider signatures matching a consumer's resolved V5 edge are reported as potential upgrade impact, not as current breakage.","Kotlin and full indirect method-chain resolution remain unsupported.","Repositories outside the authenticated team's access scope are not searched."));
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
