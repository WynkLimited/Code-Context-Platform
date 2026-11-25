package com.wynk.codecontext.port.contract.graph;

import com.wynk.codecontext.port.contract.ContractModels.*;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore.StoredIndex;
import com.wynk.codecontext.port.contract.compat.CompatibilityModels.ContractChange;
import com.wynk.codecontext.port.contract.graph.ContractGraphModels.*;
import com.wynk.codecontext.port.contract.source.KotlinAbiSurface;
import com.wynk.codecontext.port.model.ProductModels.RepoRef;
import com.wynk.codecontext.port.gnostic.GnosticModels.Confidence;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

/** Bounded resolver over the authenticated team's persisted published generations. */
public final class CrossRepoImpactResolver {
    private static final int MAX_CANDIDATES = 500;
    private static final int MAX_HOPS = 3;
    private final JdbcTemplate jdbc;
    private final PostgresContractIndexStore indexes;

    public CrossRepoImpactResolver(JdbcTemplate jdbc, PostgresContractIndexStore indexes) {
        this.jdbc = jdbc; this.indexes = indexes;
    }

    public Resolution resolve(String team, UUID originRepositoryId, ContractIndex originBefore,
                              ContractIndex originAfter, List<ContractChange> changes) {
        List<Repo> all = jdbc.query("""
SELECT r.id,r.owner,r.name,
 (SELECT j.commit_sha FROM index_jobs j WHERE j.repository_id=r.id ORDER BY j.created_at DESC LIMIT 1) AS requested_commit,
 EXISTS(SELECT 1 FROM index_jobs j WHERE j.repository_id=r.id AND j.state IN ('QUEUED','RUNNING')) AS job_pending
FROM repositories r JOIN team_repository_access a ON a.repository_id=r.id
WHERE a.team=? AND r.enabled=true AND r.id<>? ORDER BY r.owner,r.name LIMIT ?
""",(rs,n)->new Repo(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getBoolean(5)),team,originRepositoryId,MAX_CANDIDATES+1);
        boolean truncated = all.size() > MAX_CANDIDATES;
        List<Repo> candidates = all.stream().limit(MAX_CANDIDATES).toList();
        Map<UUID, Optional<StoredIndex>> loaded = new LinkedHashMap<>();
        Map<UUID, IndexFreshness> freshness = new LinkedHashMap<>();
        List<Skipped> skipped = new ArrayList<>();
        for (Repo repo : candidates) {
            Optional<StoredIndex> stored = indexes.published(repo.id);
            loaded.put(repo.id, stored);
            IndexFreshness state=indexes.freshness(stored.orElse(null));
            if(stored.isPresent()&&(repo.pending||repo.requestedCommit!=null&&!repo.requestedCommit.equals(stored.get().index().commit()))) state=IndexFreshness.STALE;
            freshness.put(repo.id,state);
            if (stored.isEmpty()) skipped.add(new Skipped(repo.ref(), "NO_INDEX"));
            else if (freshness.get(repo.id) == IndexFreshness.INVALID) skipped.add(new Skipped(repo.ref(), "INVALID_INDEX"));
            else if (freshness.get(repo.id) == IndexFreshness.STALE) skipped.add(new Skipped(repo.ref(), "STALE_INDEX"));
        }

        LinkedHashMap<String, Impacted> impacted = new LinkedHashMap<>();
        var graphRepos = new LinkedHashMap<String,RepositoryNode>();
        var graphContracts = new LinkedHashMap<String,ContractNode>();
        var graphEdges = new ArrayList<ContractEdge>();
        if (originAfter != null) graphRepos.put(originAfter.repoKey(), node(originAfter, IndexFreshness.CURRENT));
        for (ContractChange change : changes) {
            graphContracts.putIfAbsent(change.contractKey(), new ContractNode(change.identity()));
            graphEdges.add(new ContractEdge(originAfter == null ? "" : originAfter.repoKey(), change.contractKey(), EdgeKind.EXPORTS, filesFor(originAfter, change.contractKey())));
        }

        Map<String,Set<String>> directKeysByCandidate = new LinkedHashMap<>();
        for (Repo repo : candidates) {
            Optional<StoredIndex> maybe = loaded.get(repo.id);
            if (maybe.isEmpty() || freshness.get(repo.id) == IndexFreshness.INVALID) continue;
            ContractIndex consumer = maybe.get().index();
            Set<String> matchedKeys = new LinkedHashSet<>();
            Set<String> matchedFiles = new TreeSet<>();
            Confidence confidence = Confidence.HIGH;
            boolean evidenceComplete = true;
            for (ContractChange change : changes) {
                List<String> keys = MavenConsumerBridge.consumerMatchKeys(originAfter, change);
                List<String> files = MavenConsumerBridge.consumerImportFiles(consumer, keys);
                if (!files.isEmpty()) {
                    matchedKeys.add(change.contractKey()); matchedFiles.addAll(files);
                    for (ContractImport entry : consumer.imports()) if (keys.contains(entry.canonicalKey())) { confidence=min(confidence,entryConfidence(entry.metadata())); evidenceComplete &= evidenceComplete(entry.metadata())&&(entry.type()!=ContractType.MAVEN||entry.dependencyVersion()!=null&&!entry.dependencyVersion().isBlank()); }
                    graphContracts.putIfAbsent(change.contractKey(),new ContractNode(change.identity()));
                    graphEdges.add(new ContractEdge(repo.ref().key(),change.contractKey(),EdgeKind.IMPORTS,files));
                }
                for (ContractExport vendored : consumer.findExports(change.contractKey())) {
                    if (change.afterHash()!=null && change.afterHash().equals(vendored.contentHash())) continue;
                    matchedKeys.add(change.contractKey());
                    if (vendored.sourceFile()!=null) matchedFiles.add(vendored.sourceFile());
                    confidence=min(confidence,entryConfidence(vendored.metadata()));
                    evidenceComplete &= evidenceComplete(vendored.metadata());
                    graphEdges.add(new ContractEdge(repo.ref().key(),change.contractKey(),EdgeKind.EXPORTS,List.of(vendored.sourceFile()==null?"":vendored.sourceFile())));
                }
            }
            if (!matchedKeys.isEmpty()) {
                directKeysByCandidate.put(repo.ref().key(),matchedKeys);
                IndexFreshness state=freshness.get(repo.id);
                impacted.put(repo.ref().key(),new Impacted(repo.ref(),maybe.get().generationId(),consumer.commit(),consumer.indexHash(),state,
                        List.copyOf(matchedKeys),List.copyOf(matchedFiles),confidence,"DIRECT",true,evidenceComplete));
                graphRepos.put(repo.ref().key(),node(consumer,state));
            } else skipped.add(new Skipped(repo.ref(),"NO_MATCHING_IMPORT"));
        }

        // Bounded transitive walk only follows an explicitly re-exported changed contract.
        ArrayDeque<PathState> queue=new ArrayDeque<>();
        directKeysByCandidate.forEach((repo,keys)->keys.forEach(key->queue.add(new PathState(repo,key,1,impacted.get(repo).confidence()))));
        Set<String> visited=new HashSet<>();
        while(!queue.isEmpty()) {
            PathState current=queue.removeFirst(); if(current.depth>=MAX_HOPS) continue;
            String visit=current.repo+"|"+current.contractKey; if(!visited.add(visit)) continue;
            Optional<Repo> sourceRepo=candidates.stream().filter(r->r.ref().key().equals(current.repo)).findFirst();
            if(sourceRepo.isEmpty()) continue;
            Optional<StoredIndex> sourceIndex=loaded.get(sourceRepo.get().id); if(sourceIndex.isEmpty()) continue;
            List<ContractExport> reexports=sourceIndex.get().index().findExports(current.contractKey);
            if(reexports.isEmpty()) continue;
            for(Repo consumerRepo:candidates) {
                if(consumerRepo.id.equals(sourceRepo.get().id)) continue;
                Optional<StoredIndex> consumerIndex=loaded.get(consumerRepo.id); if(consumerIndex.isEmpty()) continue;
                List<String> files=consumerIndex.get().index().importingFiles(current.contractKey); if(files.isEmpty()) continue;
                String key=consumerRepo.ref().key(); Confidence conf=Confidence.LOW;
                Impacted old=impacted.get(key);
                boolean sourceEvidence=!files.isEmpty();
                if(old==null) impacted.put(key,new Impacted(consumerRepo.ref(),consumerIndex.get().generationId(),consumerIndex.get().index().commit(),consumerIndex.get().index().indexHash(),freshness.get(consumerRepo.id),List.of(current.contractKey),files,conf,"INDIRECT_CANDIDATE",false,sourceEvidence));
                else if(!"DIRECT".equals(old.relationship())) impacted.put(key,new Impacted(old.repo(),old.generationId(),old.commit(),old.indexHash(),old.freshness(),old.contractKeys(),union(old.files(),files),min(old.confidence(),conf),old.relationship(),false,old.evidenceComplete()&&sourceEvidence));
                graphRepos.put(key,node(consumerIndex.get().index(),freshness.get(consumerRepo.id)));
                graphEdges.add(new ContractEdge(key,current.contractKey,EdgeKind.IMPORTS,files));
                queue.addLast(new PathState(key,current.contractKey,current.depth+1,conf));
            }
        }
        List<SemanticMethodImpact> semanticImpacts=resolveSemanticMethodImpacts(team,originRepositoryId,originAfter,originBefore,changes,candidates,loaded,freshness);
        List<Impacted> output=impacted.values().stream().sorted(Comparator.comparing(Impacted::repo,Comparator.comparing(RepoRef::key))).toList();
        boolean stale=output.stream().anyMatch(i->i.freshness()!=IndexFreshness.CURRENT);
        boolean coverageGap=candidates.stream().anyMatch(repo->loaded.get(repo.id).isPresent()&&indexes.hasCoverageGaps(loaded.get(repo.id).get()));
        boolean unresolvedCandidate=skipped.stream().anyMatch(s->!"NO_MATCHING_IMPORT".equals(s.reason()));
        boolean staleCandidate=skipped.stream().anyMatch(s->"STALE_INDEX".equals(s.reason()));
        boolean incomplete=truncated||unresolvedCandidate||stale||coverageGap||output.stream().anyMatch(i->!i.dependencyResolved()||!i.evidenceComplete());
        AnalysisStatus status=staleCandidate||output.stream().anyMatch(i->i.freshness()==IndexFreshness.STALE)?AnalysisStatus.STALE
                :truncated?AnalysisStatus.PARTIAL
                :output.isEmpty()&&unresolvedCandidate?AnalysisStatus.UNRESOLVED
                :incomplete?AnalysisStatus.PARTIAL:AnalysisStatus.COMPLETE;
        return new Resolution(status,output,List.copyOf(skipped),truncated,all.size(),new ContractGraph(List.copyOf(graphRepos.values()),List.copyOf(graphContracts.values()),List.copyOf(graphEdges)),semanticImpacts);
    }

    private List<SemanticMethodImpact> resolveSemanticMethodImpacts(String team,UUID providerId,ContractIndex after,ContractIndex before,
            List<ContractChange> changes,List<Repo> candidates,Map<UUID,Optional<StoredIndex>> loaded,Map<UUID,IndexFreshness> freshness) {
        if(after==null||before==null)return List.of();
        UUID oldGeneration=generation(providerId,before.commit()),newGeneration=generation(providerId,after.commit());
        if(oldGeneration==null||newGeneration==null)return List.of();
        LinkedHashSet<String> removed=new LinkedHashSet<>(jdbc.query("""
SELECT old.symbol_key FROM semantic_symbols old
 WHERE old.generation_id=? AND old.symbol_kind IN ('METHOD','CONSTRUCTOR') AND old.resolution_status='RESOLVED'
   AND NOT EXISTS(SELECT 1 FROM semantic_symbols newer WHERE newer.generation_id=? AND newer.symbol_key=old.symbol_key AND newer.resolution_status='RESOLVED')
 ORDER BY old.symbol_key
""",(rs,n)->rs.getString(1),oldGeneration,newGeneration));
        // Kotlin ABI binary removals (including return-descriptor-only breaks) map onto the same V5 keys.
        for(ContractChange change:changes) {
            if(change.identity().type()!=ContractType.MAVEN)continue;
            removed.addAll(KotlinAbiSurface.removedSemanticKeys(change.beforeSignature(),change.afterSignature()));
        }
        if(removed.isEmpty())return List.of();
        var impacts=new ArrayList<SemanticMethodImpact>();
        for(Repo consumer:candidates) {
            Optional<StoredIndex> maybe=loaded.get(consumer.id);if(maybe.isEmpty()||freshness.get(consumer.id)!=IndexFreshness.CURRENT)continue;
            ContractIndex consumerIndex=maybe.get().index();
            for(ContractChange change:changes) {
                if(change.identity().type()!=ContractType.MAVEN)continue;
                String artifact=change.identity().namespace()+":"+change.identity().name();
                if(!isIndexedProviderArtifact(providerId,newGeneration,change.identity().namespace(),change.identity().name(),change.identity().version()))continue;
                if(!consumerIndex.imports().stream().anyMatch(i->i.type()==ContractType.MAVEN&&i.canonicalKey().equals(change.contractKey())))continue;
                boolean kotlinAbiPresent=hasKotlinAbi(change.beforeSignature())||hasKotlinAbi(change.afterSignature());
                boolean kotlinMetadataIncomplete=Boolean.FALSE.equals(metadataFlag(before,change,"kotlinMetadataComplete"))
                        ||Boolean.FALSE.equals(metadataFlag(after,change,"kotlinMetadataComplete"));
                for(String key:removed) {
                    List<SemanticMethodImpact> matches=jdbc.query("""
SELECT ?,?,r.owner||'/'||r.name,site.commit_sha,site.file_path,site.caller_class,site.caller_method,
       site.start_line,site.end_line,site.invocation,target.declaring_type,target.signature,
       site.dependency_artifact,site.dependency_version,edge.confidence,edge.evidence,site.extractor_version
FROM semantic_call_edges edge
JOIN semantic_call_sites site ON site.id=edge.call_site_id
JOIN semantic_symbols caller ON caller.id=edge.caller_symbol_id AND caller.repository_id=edge.repository_id AND caller.generation_id=edge.generation_id
JOIN semantic_symbols target ON target.id=edge.target_symbol_id AND target.repository_id=edge.repository_id AND target.generation_id=edge.generation_id
JOIN repositories r ON r.id=edge.repository_id
JOIN team_repository_access access ON access.repository_id=r.id
WHERE edge.repository_id=? AND edge.generation_id=? AND site.generation_id=? AND site.commit_sha=?
  AND edge.resolution_status='RESOLVED' AND caller.resolution_status='RESOLVED' AND target.resolution_status='RESOLVED'
  AND caller.symbol_key=site.caller_class||'#'||site.caller_method
  AND target.symbol_kind IN ('METHOD','CONSTRUCTOR','EXTERNAL_METHOD')
  AND target.symbol_key=? AND site.dependency_artifact LIKE ?
  AND access.team=?
  AND EXISTS(SELECT 1 FROM maven_module_dependencies dependency
    JOIN maven_module_classpaths classpath ON classpath.generation_id=dependency.generation_id AND classpath.module_path=dependency.module_path
    WHERE classpath.repository_id=edge.repository_id AND classpath.generation_id=edge.generation_id
      AND dependency.group_id||':'||dependency.artifact_id||':'||dependency.version=site.dependency_artifact
      AND dependency.version=site.dependency_version AND dependency.artifact_path IS NOT NULL)
  AND EXISTS(SELECT 1 FROM index_generations published WHERE published.id=edge.generation_id AND published.published=true)
""",(rs,n)->{
                        String extractor=rs.getString(17);
                        boolean kotlinEdge=extractor!=null&&extractor.startsWith("kotlin-compiler");
                        String completeness=kotlinMetadataIncomplete?"INCOMPLETE_KOTLIN_METADATA"
                                :kotlinEdge||kotlinAbiPresent?"PUBLISHED_KOTLIN_JVM_EDGE":"PUBLISHED_V5_EDGE";
                        String provenance=kotlinEdge
                                ?"KOTLIN_COMPILER_JVM; MAVEN_RESOLVER; V5_SEMANTIC_CALL_EDGE; KOTLIN_SOURCE_COMPATIBILITY_UNKNOWN"
                                :(kotlinAbiPresent
                                ?"JAVAPARSER_SYMBOL_SOLVER; MAVEN_RESOLVER; V5_SEMANTIC_CALL_EDGE; KOTLIN_ABI_BINARY; KOTLIN_SOURCE_COMPATIBILITY_UNKNOWN"
                                :"JAVAPARSER_SYMBOL_SOLVER; MAVEN_RESOLVER; V5_SEMANTIC_CALL_EDGE");
                        return new SemanticMethodImpact(after.repoKey(),after.commit(),consumer.ref().key(),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getInt(8),rs.getInt(9),rs.getString(10),rs.getString(11),rs.getString(12),null,null,rs.getString(13),rs.getString(14),rs.getDouble(15),rs.getString(16),"POTENTIAL_UPGRADE_IMPACT","CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN","DIRECT","RESOLVED",freshness.get(consumer.id).name(),completeness,provenance,false);
                    },after.repoKey(),after.commit(),consumer.id,maybe.get().generationId(),maybe.get().generationId(),consumerIndex.commit(),key,artifact+":%",team);
                    for(var match:matches) {
                        String baselineVersion=mavenVersion(before,change);
                        String current=currentCompatibility(match.dependencyVersion(),baselineVersion,change.identity().version());
                        impacts.add(new SemanticMethodImpact(match.providerRepository(),match.providerCommit(),match.consumerRepository(),match.consumerCommit(),match.filePath(),match.callerClass(),match.callerMethod(),match.startLine(),match.endLine(),match.invocation(),match.providerClass(),match.oldSignature(),change.beforeSignature(),change.afterSignature(),match.dependencyArtifact(),match.dependencyVersion(),match.confidence(),match.evidence(),match.classification(),current,match.relationship(),match.resolutionState(),match.freshness(),match.analysisCompleteness(),match.provenance(),false));
                    }
                }
            }
        }
        impacts.sort(Comparator
                .comparing(SemanticMethodImpact::consumerRepository)
                .thenComparing(SemanticMethodImpact::filePath,Comparator.nullsLast(String::compareTo))
                .thenComparing(SemanticMethodImpact::callerClass,Comparator.nullsLast(String::compareTo))
                .thenComparing(SemanticMethodImpact::startLine));
        return List.copyOf(impacts);
    }

    private static boolean hasKotlinAbi(String signature) {
        return signature!=null&&signature.contains(KotlinAbiSurface.BINARY_SIGNATURE_PREFIX);
    }

    private static Object metadataFlag(ContractIndex index,ContractChange change,String key) {
        if(index==null||change==null)return null;
        return index.findExports(change.contractKey()).stream().map(export->export.metadata().get(key)).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static String mavenVersion(ContractIndex index,ContractChange change) {
        List<String> versions=index.exportsOf(ContractType.MAVEN).stream()
                .filter(export->export.canonicalKey().equals(change.contractKey()))
                .map(export->export.contract().version()).filter(Objects::nonNull).distinct().toList();
        return versions.size()==1?versions.get(0):null;
    }

    static String currentCompatibility(String dependencyVersion,String baselineVersion,String providerHeadVersion) {
        if(dependencyVersion==null)return "DEPENDENCY_VERSION_UNKNOWN";
        if(Objects.equals(dependencyVersion,providerHeadVersion))return "DEPENDENCY_VERSION_MATCHES_PROVIDER_HEAD";
        if(baselineVersion!=null&&Objects.equals(dependencyVersion,baselineVersion))return "CURRENT_DEPENDENCY_NOT_PROVEN_BROKEN";
        return "DEPENDENCY_REMAINS_PINNED_TO_OLDER_OR_DIFFERENT_RELEASE";
    }

    /** An artifact is attributed to a provider only when its published exact generation declares
     * the same resolved Maven coordinates. Repository-name similarity is never used as a mapping. */
    private boolean isIndexedProviderArtifact(UUID providerId,UUID providerGeneration,String groupId,String artifactId,String version) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
SELECT EXISTS(SELECT 1 FROM maven_module_classpaths
 WHERE repository_id=? AND generation_id=? AND resolution_status='RESOLVED'
   AND group_id=? AND artifact_id=? AND version=?)
""",Boolean.class,providerId,providerGeneration,groupId,artifactId,version));
    }

    private UUID generation(UUID repository,String commit) {
        List<UUID> ids=jdbc.query("""
SELECT g.id FROM index_generations g
WHERE g.repository_id=? AND g.commit_sha=?
  AND (g.published=true OR EXISTS(SELECT 1 FROM index_jobs j WHERE j.repository_id=g.repository_id AND j.commit_sha=g.commit_sha AND j.state='SUCCEEDED'))
ORDER BY g.generation DESC LIMIT 1
""",(rs,n)->rs.getObject(1,UUID.class),repository,commit);
        return ids.isEmpty()?null:ids.get(0);
    }

    private static Confidence entryConfidence(Map<String,Object> metadata) {
        String binding=String.valueOf(metadata.getOrDefault("graphBindingStatus","UNRESOLVED"));
        if("AMBIGUOUS".equals(binding)) return Confidence.LOW;
        String type=String.valueOf(metadata.getOrDefault("evidenceType",""));
        return switch(type) {
            case "DETERMINISTIC","BUILD_METADATA","PACKAGE_METADATA","RUNTIME_EVIDENCE" -> Confidence.HIGH;
            case "AST_ANALYSIS","STATIC_ANALYSIS","CONTRACT_ANALYSIS" -> Confidence.MEDIUM;
            case "SEMANTIC_INFERENCE","LLM_INFERENCE","MANUAL" -> Confidence.LOW;
            default -> Confidence.LOW;
        };
    }
    private static Confidence min(Confidence a,Confidence b){return a.ordinal()>=b.ordinal()?a:b;}
    private static boolean evidenceComplete(Map<String,Object> metadata) {
        return Boolean.TRUE.equals(metadata.get("evidenceComplete")) && !"AMBIGUOUS".equals(metadata.get("graphBindingStatus"));
    }
    private static RepositoryNode node(ContractIndex i,IndexFreshness f){return new RepositoryNode(i.owner(),i.repository(),i.commit(),i.branch(),i.indexHash(),f);}
    private static List<String> filesFor(ContractIndex i,String key){return i==null?List.of():i.findExports(key).stream().map(ContractExport::sourceFile).filter(Objects::nonNull).distinct().toList();}
    private static List<String> union(List<String>a,List<String>b){var s=new TreeSet<String>(a);s.addAll(b);return List.copyOf(s);}
    private record Repo(UUID id,String owner,String name,String requestedCommit,boolean pending){RepoRef ref(){return new RepoRef(owner,name);}}
    private record PathState(String repo,String contractKey,int depth,Confidence confidence){}
    public enum AnalysisStatus { COMPLETE, PARTIAL, INCOMPLETE, UNRESOLVED, STALE, FAILED }
    public record Impacted(RepoRef repo,UUID generationId,String commit,String indexHash,IndexFreshness freshness,List<String> contractKeys,List<String> files,Confidence confidence,String relationship,boolean dependencyResolved,boolean evidenceComplete){}
    public record Skipped(RepoRef repo,String reason){}
    public record SemanticMethodImpact(String providerRepository,String providerCommit,String consumerRepository,String consumerCommit,String filePath,String callerClass,String callerMethod,int startLine,int endLine,String invocation,String providerClass,String oldSignature,String providerOldApiSignature,String providerNewApiSignature,String dependencyArtifact,String dependencyVersion,double confidence,String evidence,String classification,String currentCompatibility,String relationship,String resolutionState,String freshness,String analysisCompleteness,String provenance,boolean blocking){}
    public record Resolution(AnalysisStatus status,List<Impacted> impacted,List<Skipped> skipped,boolean truncated,int candidateCount,ContractGraph graph,List<SemanticMethodImpact> semanticMethodImpacts){}
}
