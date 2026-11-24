package com.wynk.codecontext.index;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.port.contract.ContractIndexBuilder;
import com.wynk.codecontext.port.contract.ContractModels;
import com.wynk.codecontext.port.contract.PostgresContractIndexStore;
import com.wynk.codecontext.port.contract.RepositoryContext;
import com.wynk.codecontext.port.contract.graph.GraphSymbolBinder;
import tools.jackson.databind.json.JsonMapper;
import com.wynk.codecontext.repo.GitRepositoryManager;
import com.wynk.codecontext.repo.RepositoryStore;
import com.wynk.codecontext.search.ZoektIndexer;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;

import java.nio.charset.StandardCharsets;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class IndexingService {
    private static final Logger LOGGER = LoggerFactory.getLogger(IndexingService.class);
    private static final Pattern KAFKA_PRODUCER = Pattern.compile("(?:\\.send|\\.sendAsync)\\s*\\(\\s*[\"']([A-Za-z0-9._-]{2,150})[\"']");
    private static final Pattern KAFKA_CONSUMER = Pattern.compile("@KafkaListener\\s*\\([^)]{0,1000}?topics?\\s*=\\s*(?:\\{\\s*)?[\"']([A-Za-z0-9._-]{2,150})[\"']");
    private static final Pattern ROUTE = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?[\"']([^\"']+)");
    private static final Pattern REST_CLIENT = Pattern.compile("\\.(getForObject|getForEntity|postForObject|postForEntity|put|delete|exchange)\\s*\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern WEBCLIENT_URI = Pattern.compile("\\.uri\\(\\s*[\"']([^\"']+)[\"']");
    private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+)\\s*;?");
    private final JdbcTemplate jdbc; private final RepositoryStore repos; private final GitRepositoryManager git;
    private final CodeContextProperties props; private final EmbeddingModel embeddings; private final ZoektIndexer zoekt;
    private final GraphifyIndexer graphify; private final TransactionTemplate transactions;
    private final ContractIndexBuilder contractIndexBuilder; private final JsonMapper objectMapper; private final GraphSymbolBinder graphSymbolBinder; private final ConsumerContractIndexRefresher contractRefresher; private final PostgresContractIndexStore contractIndexes;
    private final MavenClasspathResolver mavenClasspathResolver = new MavenClasspathResolver();
    private final java.util.concurrent.ThreadPoolExecutor workers;private final java.util.concurrent.Semaphore slots;private final java.util.concurrent.ConcurrentHashMap<UUID,Object> repositoryLocks=new java.util.concurrent.ConcurrentHashMap<>();
    public IndexingService(JdbcTemplate j, RepositoryStore r, GitRepositoryManager g, CodeContextProperties p, EmbeddingModel e, ZoektIndexer zoekt,GraphifyIndexer graphify,org.springframework.transaction.PlatformTransactionManager tx, ContractIndexBuilder contractIndexBuilder, JsonMapper objectMapper, GraphSymbolBinder graphSymbolBinder, ConsumerContractIndexRefresher contractRefresher, PostgresContractIndexStore contractIndexes) { jdbc=j;repos=r;git=g;props=p;embeddings=e;this.zoekt=zoekt;this.graphify=graphify;this.transactions=new TransactionTemplate(tx);this.contractIndexBuilder=contractIndexBuilder;this.objectMapper=objectMapper;this.graphSymbolBinder=graphSymbolBinder;this.contractRefresher=contractRefresher;this.contractIndexes=contractIndexes;int n=Math.max(1,Math.min(16,p.maxIndexConcurrency()));this.slots=new java.util.concurrent.Semaphore(n);this.workers=new java.util.concurrent.ThreadPoolExecutor(n,n,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(n),workerFactory->{Thread t=new Thread(workerFactory,"code-context-indexer");t.setDaemon(true);return t;}); }

    @Scheduled(fixedDelayString="${INDEX_POLL_MS:1500}")
    public void poll() {
        jdbc.update("UPDATE index_jobs SET state=CASE WHEN attempt>=3 THEN 'FAILED' ELSE 'QUEUED' END,error='worker lease expired',updated_at=now() WHERE state='RUNNING' AND updated_at < now() - interval '1 hour'");
        while(slots.tryAcquire()) {
            List<UUID> ids=jdbc.query("""
WITH candidate AS (SELECT id FROM index_jobs WHERE state='QUEUED' AND available_at<=now() ORDER BY available_at,created_at FOR UPDATE SKIP LOCKED LIMIT 1)
              UPDATE index_jobs SET state='RUNNING',attempt=attempt+1,updated_at=now() WHERE id=(SELECT id FROM candidate) RETURNING id""",(rs,n)->rs.getObject(1,UUID.class));
            if(ids.isEmpty()){slots.release();break;}
            try{workers.execute(()->{try{process(ids.get(0));}finally{slots.release();}});}catch(RuntimeException ex){slots.release();jdbc.update("UPDATE index_jobs SET state='QUEUED',error='worker queue unavailable',updated_at=now() WHERE id=?",ids.get(0));}
        }
    }
    public UUID enqueue(String owner,String name,String sha,String delivery) {
        RepositoryRecord repo=repos.find(owner,name).orElseThrow();
        String key=(delivery==null||delivery.isBlank()?repo.id()+":"+sha:delivery);
        return jdbc.queryForObject("INSERT INTO index_jobs(repository_id,commit_sha,idempotency_key,state) VALUES(?,?,?,'QUEUED') ON CONFLICT(idempotency_key) DO UPDATE SET idempotency_key=excluded.idempotency_key RETURNING id",(rs,n)->rs.getObject(1,UUID.class),repo.id(),sha,key);
    }
    private void process(UUID jobId) {
        Job job=jdbc.queryForObject("SELECT repository_id,commit_sha,attempt FROM index_jobs WHERE id=?",(rs,n)->new Job(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3)),jobId);
        synchronized(repositoryLocks.computeIfAbsent(job.repositoryId,k->new Object())){processLocked(jobId,job);}
    }
    private void processLocked(UUID jobId,Job job) {
        try {
            Boolean enabled=jdbc.queryForObject("SELECT enabled FROM repositories WHERE id=?",Boolean.class,job.repositoryId);
            if(!Boolean.TRUE.equals(enabled)){jdbc.update("UPDATE index_jobs SET state='FAILED',error='repository disabled',updated_at=now() WHERE id=?",jobId);return;}
            RepositoryRecord repo=jdbc.queryForObject("SELECT id,owner,name,clone_url,team,default_branch,repository_type,mirror_path FROM repositories WHERE id=?",(rs,n)->new RepositoryRecord(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8)),job.repositoryId);
            Path root=git.sync(repo); String sha=git.head(root);
            String published=jdbc.query("SELECT commit_sha FROM index_generations WHERE repository_id=? AND published",(rs,n)->rs.getString(1),repo.id()).stream().findFirst().orElse(null);
            if(sha.equals(published)) {
                var currentIndex=contractIndexes.published(repo.id());
                if(currentIndex.isPresent() && contractIndexes.freshness(currentIndex.get())==ContractModels.IndexFreshness.CURRENT) {
                    jdbc.update("UPDATE index_jobs SET state='SUCCEEDED',updated_at=now() WHERE id=?",jobId);return;
                }
            }
            List<GitRepositoryManager.SourceFile> files=git.filesAt(root,sha);
            Long generation=jdbc.queryForObject("SELECT coalesce(max(generation),0)+1 FROM index_generations WHERE repository_id=?",Long.class,repo.id());
            UUID gen=jdbc.queryForObject("INSERT INTO index_generations(repository_id,commit_sha,generation,published) VALUES(?,?,?,false) RETURNING id",(rs,n)->rs.getObject(1,UUID.class),repo.id(),sha,generation);
            UUID previous=jdbc.query("SELECT id FROM index_generations WHERE repository_id=? AND published",(rs,n)->rs.getObject(1,UUID.class),repo.id()).stream().findFirst().orElse(null);
            jdbc.batchUpdate("INSERT INTO generation_files(generation_id,file_path,blob_sha) VALUES(?,?,?)",files,500,(ps,f)->{ps.setObject(1,gen);ps.setString(2,f.path());ps.setString(3,f.blobSha());});
            Map<String,String> oldFiles=previous==null?Map.of():jdbc.query("SELECT file_path,blob_sha FROM generation_files WHERE generation_id=?",rs->{Map<String,String> m=new HashMap<>();while(rs.next())m.put(rs.getString(1),rs.getString(2));return m;},previous);
            for(var f:files) {
                if(!f.blobSha().equals(oldFiles.get(f.path())))indexFile(repo,gen,f);
            }
            if(previous!=null)copyUnchangedFiles(previous,gen);
            if(props.graphify().enabled()) {
                Path snapshot=Files.createTempDirectory("code-context-struct-");
                try {
                    for(var f:files){Path out=snapshot.resolve(f.path()).normalize();if(!out.startsWith(snapshot)||f.path().startsWith("/"))throw new IllegalArgumentException("unsafe repository path");Files.createDirectories(out.getParent());Files.writeString(out,f.content());}
                    graphify.buildAndPersist(snapshot,gen);
                } finally {try(var paths=Files.walk(snapshot)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
            }
            buildAndPersistSemanticCallSites(repo,gen,files,sha);
            buildAndPersistContractIndex(repo,gen,files,sha);
            zoekt.index(repo,root,gen);
            // Atomic visibility: previous generation stays published until every stage above succeeds.
            transactions.execute(status->{jdbc.update("UPDATE index_generations SET published=false WHERE repository_id=?",repo.id());
                int publishedRows=jdbc.update("UPDATE index_generations SET published=true WHERE id=? AND EXISTS(SELECT 1 FROM repositories r WHERE r.id=index_generations.repository_id AND r.enabled=true)",gen);
                if(publishedRows!=1)throw new IllegalStateException("repository disabled before generation publication");
                contractRefresher.pruneSupersededRelationships();
                jdbc.update("UPDATE index_jobs SET state='SUCCEEDED',updated_at=now() WHERE id=?",jobId);return null;});
            List<UUID> obsoleteZoektGenerations=jdbc.query("SELECT id FROM index_generations WHERE repository_id=? AND published=false",(rs,n)->rs.getObject(1,UUID.class),repo.id());
            for(UUID obsolete:obsoleteZoektGenerations) {
                try { zoekt.removeGeneration(repo,obsolete); }
                catch(Exception cleanupFailure) {
                    LOGGER.warn("Zoekt obsolete generation cleanup failed for repo={} generation={}: {}",repo.key(),obsolete,cleanupFailure.toString());
                }
            }
        } catch(Exception e) {
            String sqlDiagnostics = sqlDiagnosticChain(e);
            if (!sqlDiagnostics.isBlank()) LOGGER.error("Database failure while indexing job {}: {}", jobId, sqlDiagnostics);
            int retryDelay=Math.min(300,5*(1<<Math.max(0,job.attempt-1)));
            jdbc.update("UPDATE index_jobs SET state=CASE WHEN attempt>=3 THEN 'FAILED' ELSE 'QUEUED' END,error=?,available_at=now()+(? * interval '1 second'),updated_at=now() WHERE id=?",truncate(e.getMessage()),retryDelay,jobId);
        }
    }
    @PreDestroy public void stopWorkers(){workers.shutdownNow();}
    private void copyUnchangedFiles(UUID previous,UUID next) {
        jdbc.update("""
INSERT INTO code_nodes(generation_id,node_type,symbol,qualified_name,file_path,start_line,end_line,language,metadata)
          SELECT ?,old.node_type,old.symbol,old.qualified_name,old.file_path,old.start_line,old.end_line,old.language,old.metadata
          FROM code_nodes old JOIN generation_files prev_file ON prev_file.generation_id=old.generation_id AND prev_file.file_path=old.file_path
          WHERE old.generation_id=? AND NOT jsonb_exists(old.metadata,'id') AND EXISTS(SELECT 1 FROM generation_files nf WHERE nf.generation_id=? AND nf.file_path=old.file_path AND nf.blob_sha=prev_file.blob_sha)""",next,previous,next);
        jdbc.update("""
INSERT INTO code_chunks(generation_id,file_path,symbol,start_line,end_line,content_hash,embedding,content)
          SELECT ?,old.file_path,old.symbol,old.start_line,old.end_line,old.content_hash,old.embedding,old.content FROM code_chunks old
          JOIN generation_files prev_file ON prev_file.generation_id=old.generation_id AND prev_file.file_path=old.file_path
          JOIN generation_files nf ON nf.generation_id=? AND nf.file_path=old.file_path AND nf.blob_sha=prev_file.blob_sha WHERE old.generation_id=?""",next,next,previous);
        jdbc.update("""
INSERT INTO code_edges(generation_id,source_node,target_node,edge_type,evidence,confidence)
          SELECT ?,ns.id,nt.id,e.edge_type,e.evidence,e.confidence FROM code_edges e
          JOIN code_nodes os ON os.id=e.source_node JOIN code_nodes ot ON ot.id=e.target_node
          JOIN code_nodes ns ON ns.generation_id=? AND ns.file_path=os.file_path AND ns.qualified_name=os.qualified_name AND ns.start_line=os.start_line
          JOIN code_nodes nt ON nt.generation_id=? AND nt.file_path=ot.file_path AND nt.qualified_name=ot.qualified_name AND nt.start_line=ot.start_line
          WHERE e.generation_id=? AND NOT jsonb_exists(os.metadata,'id') AND NOT jsonb_exists(ot.metadata,'id') ON CONFLICT DO NOTHING""",next,next,next,previous);
    }
    private void indexFile(RepositoryRecord repo,UUID gen,GitRepositoryManager.SourceFile file) throws Exception {
        String ext=file.path().contains(".")?file.path().substring(file.path().lastIndexOf('.')+1):"";
        List<Symbol> symbols=new ArrayList<>();
        if(ext.equals("java")) try {
            var cu=StaticJavaParser.parse(file.content());
            for(var c:cu.findAll(ClassOrInterfaceDeclaration.class)) symbols.add(new Symbol("CLASS",c.getNameAsString(),c.getFullyQualifiedName().orElse(c.getNameAsString()),line(c.getBegin()),line(c.getEnd()),classHeader(c)));
            for(var m:cu.findAll(MethodDeclaration.class)) symbols.add(new Symbol("METHOD",m.getNameAsString(),m.getSignature().asString(),line(m.getBegin()),line(m.getEnd()),m.toString()));
            for(var c:cu.findAll(com.github.javaparser.ast.body.ConstructorDeclaration.class)) symbols.add(new Symbol("CONSTRUCTOR",c.getNameAsString(),c.getSignature().asString(),line(c.getBegin()),line(c.getEnd()),c.toString()));
        } catch(RuntimeException ignored) { /* malformed source remains searchable and semantically chunked */ }
        if(symbols.isEmpty()) symbols.add(new Symbol("FILE",file.path(),file.path(),1,Math.max(1,(int)file.content().lines().count()),file.content()));
        for(Symbol symbol:symbols) {
            long node=jdbc.queryForObject("INSERT INTO code_nodes(generation_id,node_type,symbol,qualified_name,file_path,start_line,end_line,language,metadata) VALUES(?,?,?,?,?,?,?,?,jsonb_build_object('blobSha',?)) RETURNING id",Long.class,gen,symbol.type,symbol.name,symbol.qualified,file.path(),symbol.start,symbol.end,ext,file.blobSha());
            for(Chunk part:split(symbol.text,symbol.start,props.maxChunkChars())) {
                String chunk="repo: "+repo.key()+" | file: "+file.path()+"\n"+symbol.type.toLowerCase()+": "+symbol.qualified+"\n---\n"+part.text;
                String hash=sha256(chunk);float[] vector=embeddings.embed(chunk);
                if(vector.length!=props.embeddingDimensions())throw new IllegalStateException("embedding dimension mismatch: expected "+props.embeddingDimensions()+" got "+vector.length);
                jdbc.update("INSERT INTO code_chunks(generation_id,file_path,symbol,start_line,end_line,content_hash,embedding,content) VALUES(?,?,?,?,?,?,?::vector,?) ON CONFLICT DO NOTHING",gen,file.path(),symbol.qualified,part.start,part.end,hash,vectorLiteral(vector),chunk);
            }
        }
    }

    /** Resolve against source roots and Maven descriptors materialized from this immutable Git
     * commit. External artifacts are deliberately absent until the repository's Maven classpath
     * is resolved. */
    private void buildAndPersistSemanticCallSites(RepositoryRecord repo, UUID generation,
                                                   List<GitRepositoryManager.SourceFile> files, String commit) throws Exception {
        Path snapshot=Files.createTempDirectory("code-context-semantic-");
        try {
            List<GitRepositoryManager.SourceFile> javaFiles=files.stream().filter(f->f.path().endsWith(".java")).toList();
            for(var file:files){
                if(!file.path().endsWith(".java")&&!file.path().equals("pom.xml")&&!file.path().endsWith("/pom.xml"))continue;
                Path out=snapshot.resolve(file.path()).normalize();if(!out.startsWith(snapshot)||file.path().startsWith("/"))throw new IllegalArgumentException("unsafe repository path");Files.createDirectories(out.getParent());Files.writeString(out,file.content());
            }
            var roots=new LinkedHashSet<Path>();
            for(var f:javaFiles){
                int marker=f.path().indexOf("src/main/java/"); if(marker<0)marker=f.path().indexOf("src/test/java/");
                if(marker>=0)roots.add(snapshot.resolve(f.path().substring(0,marker)+(f.path().contains("src/test/java/")?"src/test/java":"src/main/java")));
            }
            if(roots.isEmpty()&&!javaFiles.isEmpty())roots.add(snapshot);
            Path mavenCache=Path.of(props.repositoryRoot()).toAbsolutePath().normalize().resolve(".maven-cache");
            var mavenResult=mavenClasspathResolver.resolve(snapshot,commit,mavenCache,MavenClasspathResolver.configuredRepositories());
            persistMavenResolution(repo,generation,commit,mavenResult);
            for(var file:javaFiles){
                Path source=snapshot.resolve(file.path()).normalize();
                String module=moduleFor(snapshot,file.path(),mavenResult.modules());
                var moduleCp=mavenResult.modules().stream().filter(m->m.modulePath().equals(module)).findFirst().orElse(null);
                List<Path> jars=moduleCp==null?List.of():moduleCp.jars();
                List<Path> moduleRoots=roots.stream().filter(p->{String relative=snapshot.relativize(p).toString().replace(File.separatorChar,'/');return module.isBlank()||relative.equals(module)||relative.startsWith(module+"/");}).toList();
                if(moduleRoots.isEmpty())moduleRoots=List.of(snapshot);
                for(var declaration:com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.declaredMethods(source,moduleRoots,jars))
                    ensureSemanticSymbol(repo,generation,commit,declaration.declaringType()+"#"+declaration.signature(),declaration.declaringType(),declaration.signature(),declaration.kind(),"RESOLVED");
                var result=com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.analyze(source,moduleRoots,jars);
                if(!result.parseComplete()){
                    jdbc.update("INSERT INTO semantic_call_sites(repository_id,generation_id,commit_sha,file_path,caller_class,caller_method,start_line,end_line,invocation,resolution_status,evidence,confidence,extractor_version) VALUES(?,?,?,?,NULL,NULL,1,1,?,'INCOMPLETE',?,0,'javaparser-symbol-solver-3.27.1')",
                            repo.id(),generation,commit,file.path(),"<file-analysis-incomplete>",result.failureReason());
                    continue;
                }
                for(var call:result.callSites()){
                    String artifact=artifactFor(call.declaringType(),moduleCp); String version=artifact==null?null:artifact.substring(artifact.lastIndexOf(':')+1);
                    String evidence=call.evidence()+(moduleCp==null?"; no Maven module classpath":"; module="+module+"; classpath="+moduleCp.fingerprint());
                    Long caller=call.callerClass()==null||call.callerMethod()==null?null:ensureSemanticSymbol(repo,generation,commit,
                            call.callerClass()+"#"+call.callerMethod(),call.callerClass(),call.callerMethod(),"METHOD","RESOLVED");
                    Long target=call.resolutionStatus().equals("RESOLVED")&&call.declaringType()!=null&&call.signature()!=null
                            ?ensureSemanticSymbol(repo,generation,commit,call.declaringType()+"#"+call.signature(),call.declaringType(),call.signature(),"METHOD","RESOLVED"):null;
                    persistSemanticCallSite(repo.id(),generation,commit,file.path(),call,evidence,caller,target,artifact,version);
                }
            }
        } finally {try(var paths=Files.walk(snapshot)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(Exception ignored){}});}}
    }

    Long persistSemanticCallSite(UUID repositoryId, UUID generationId, String commitSha, String filePath,
                                 com.wynk.codecontext.port.contract.source.JavaSemanticCallSiteAnalyzer.CallSite call,
                                 String evidence, Long callerSymbolId, Long targetSymbolId,
                                 String dependencyArtifact, String dependencyVersion) throws Exception {
        double confidence = "RESOLVED".equals(call.resolutionStatus()) ? 0.85 : 0.0;
        Long callId = jdbc.queryForObject("""
INSERT INTO semantic_call_sites(repository_id,generation_id,commit_sha,file_path,caller_class,caller_method,
 start_line,end_line,invocation,declaring_type,resolved_signature,parameter_types,return_type,
 resolution_status,evidence,confidence,extractor_version,caller_symbol_id,target_symbol_id,dependency_artifact,dependency_version)
VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,'javaparser-symbol-solver-3.27.1',?,?,?,?) RETURNING id
""", Long.class,
                repositoryId,generationId,commitSha,filePath,call.callerClass(),call.callerMethod(),
                Math.max(1,call.startLine()),Math.max(1,call.endLine()),call.expression(),call.declaringType(),
                call.signature(),objectMapper.writeValueAsString(call.parameterTypes()),call.returnType(),
                call.resolutionStatus(),evidence,confidence,callerSymbolId,targetSymbolId,dependencyArtifact,dependencyVersion);
        jdbc.update("INSERT INTO semantic_call_edges(repository_id,generation_id,call_site_id,caller_symbol_id,target_symbol_id,resolution_status,evidence,confidence) VALUES(?,?,?,?,?,?,?,?)",
                repositoryId,generationId,callId,callerSymbolId,targetSymbolId,call.resolutionStatus(),evidence,confidence);
        return callId;
    }

    private static String moduleFor(Path snapshot,String file,List<MavenClasspathResolver.ModuleClasspath> modules) {
        return modules.stream().map(MavenClasspathResolver.ModuleClasspath::modulePath).filter(p->!p.isBlank()&&(file.equals(p+"/pom.xml")||file.startsWith(p+"/"))).max(Comparator.comparingInt(String::length)).orElse("");
    }
    private static String artifactFor(String declaringType,MavenClasspathResolver.ModuleClasspath classpath) {
        if(classpath==null)return null;
        if(declaringType==null)return null;String entry=declaringType.replace('.','/')+".class";
        for(var dependency:classpath.dependencies())try(var zip=new java.util.zip.ZipFile(dependency.artifact().toFile())){if(zip.getEntry(entry)!=null)return dependency.groupId()+":"+dependency.artifactId()+":"+dependency.version();}catch(Exception ignored){}
        return null;
    }
    private void persistMavenResolution(RepositoryRecord repo,UUID generation,String commit,MavenClasspathResolver.Result result) throws Exception {
        if(result.modules().isEmpty()) {
            if(!result.complete()) jdbc.update("INSERT INTO maven_module_classpaths(repository_id,generation_id,commit_sha,module_path,resolver_version,resolution_status,reason) VALUES(?,?,?,'',?,'INCOMPLETE',?) ON CONFLICT(generation_id,module_path) DO UPDATE SET resolution_status='INCOMPLETE',reason=excluded.reason,resolved_at=now()",
                    repo.id(),generation,commit,"maven-resolver-1.9.22",result.reason());
            return;
        }
        for(var module:result.modules()) {
            jdbc.update("INSERT INTO maven_module_classpaths(repository_id,generation_id,commit_sha,module_path,group_id,artifact_id,version,packaging,classpath_fingerprint,resolver_version,resolution_status,reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(generation_id,module_path) DO UPDATE SET group_id=excluded.group_id,artifact_id=excluded.artifact_id,version=excluded.version,packaging=excluded.packaging,classpath_fingerprint=excluded.classpath_fingerprint,resolver_version=excluded.resolver_version,resolution_status=excluded.resolution_status,reason=excluded.reason,resolved_at=now()",
                    repo.id(),generation,commit,module.modulePath(),module.groupId(),module.artifactId(),module.version(),module.packaging(),module.fingerprint(),"maven-resolver-1.9.22",module.status(),module.reason());
            jdbc.update("DELETE FROM maven_module_dependencies WHERE generation_id=? AND module_path=?",generation,module.modulePath());
            for(var dependency:module.dependencies()) jdbc.update("INSERT INTO maven_module_dependencies(generation_id,module_path,group_id,artifact_id,version,classifier,scope,transitive,optional,artifact_path,artifact_sha256,exclusions) VALUES(?,?,?,?,?,?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                    generation,module.modulePath(),dependency.groupId(),dependency.artifactId(),dependency.version(),dependency.classifier(),dependency.scope(),dependency.transitive(),dependency.optional(),dependency.artifact().toString(),dependency.sha256(),"[]");
        }
    }

    private Long ensureSemanticSymbol(RepositoryRecord repo,UUID generation,String commit,String key,String declaringType,
                                      String signature,String kind,String status) {
        return jdbc.queryForObject("INSERT INTO semantic_symbols(repository_id,generation_id,commit_sha,symbol_key,declaring_type,signature,symbol_kind,resolution_status) VALUES(?,?,?,?,?,?,?,?) ON CONFLICT(generation_id,symbol_key) DO UPDATE SET resolution_status=CASE WHEN semantic_symbols.resolution_status='RESOLVED' THEN semantic_symbols.resolution_status ELSE excluded.resolution_status END RETURNING id",Long.class,
                repo.id(),generation,commit,key,declaringType,signature,kind,status);
    }

    private void buildAndPersistContractIndex(RepositoryRecord repo, UUID generation, List<GitRepositoryManager.SourceFile> files, String commit) throws Exception {
        Path snapshot = Files.createTempDirectory("code-context-contract-");
        ContractModels.ContractIndex index;
        try {
            for (var file : files) {
                Path out = snapshot.resolve(file.path()).normalize();
                if (!out.startsWith(snapshot) || file.path().startsWith("/")) throw new IllegalArgumentException("unsafe repository path");
                Files.createDirectories(out.getParent());
                Files.writeString(out, file.content());
            }
            var context = new RepositoryContext(repo.owner(), repo.name(), commit, repo.defaultBranch(), snapshot);
            index = graphSymbolBinder.bind(contractIndexBuilder.build(context), generation);
        } finally {
            try (var paths = Files.walk(snapshot)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) { } });
            }
        }
        String extractorVersions = objectMapper.writeValueAsString(index.extractorVersions());
        jdbc.update("INSERT INTO contract_index_metadata(generation_id,index_schema_version,extractor_versions,compatibility_rules_version,generator_version,generated_at,index_hash,export_count,import_count) VALUES(?,?,?::jsonb,?,?,?,?,?,?)",
                generation, index.indexSchemaVersion(), extractorVersions, index.compatibilityRulesVersion(), index.generatorVersion(), Timestamp.from(index.generatedAt()), index.indexHash(), index.exports().size(), index.imports().size());
        for (var entry : index.exports()) {
            Map<String,Object> storedMetadata=storedMetadata(entry.metadata(),entry.sourceFile(),entry.symbol(),entry.contract().version());
            String metadata = objectMapper.writeValueAsString(storedMetadata);
            jdbc.update("INSERT INTO contracts(generation_id,contract_type,canonical_key,role,symbol,file_path,line,dependency_version,evidence,confidence,content_hash,metadata) VALUES(?,?,?,'EXPORT',?,?,?,?,?,?,?,?::jsonb)",
                    generation, entry.type().name(), entry.canonicalKey(), entry.symbol(), safePath(entry.sourceFile()), sourceLine(entry.metadata()), entry.contract().version(), evidence(entry.symbol(), entry.metadata()), extractionConfidence(entry.metadata()), contentHash(entry.canonicalKey(), entry.contentHash()), metadata);
        }
        for (var entry : index.imports()) {
            Map<String,Object> storedMetadata=storedMetadata(entry.metadata(),entry.sourceFile(),entry.symbol(),entry.contract().version());
            String metadata = objectMapper.writeValueAsString(storedMetadata);
            jdbc.update("INSERT INTO contracts(generation_id,contract_type,canonical_key,role,symbol,file_path,line,dependency_version,evidence,confidence,content_hash,metadata) VALUES(?,?,?,'IMPORT',?,?,?,?,?,?,?,?::jsonb)",
                    generation, entry.type().name(), entry.canonicalKey(), entry.symbol(), safePath(entry.sourceFile()), sourceLine(entry.metadata()), entry.dependencyVersion(), evidence(entry.symbol(), entry.metadata()), extractionConfidence(entry.metadata()), sha256(entry.canonicalKey()), metadata);
        }
        contractRefresher.refreshGeneration(generation);
    }

    private static String safePath(String path) { return path == null || path.isBlank() ? "<unknown>" : path; }
    private static int sourceLine(Map<String,Object> metadata) {
        Object range = metadata.get("sourceRange");
        if (range instanceof Map<?,?> map && map.get("startLine") instanceof Number n) return Math.max(1, n.intValue());
        if (metadata.get("boundStartLine") instanceof Number n) return Math.max(1,n.intValue());
        return 1;
    }
    private static String evidence(String symbol, Map<String,Object> metadata) {
        Object kind = metadata.get("evidenceType");
        return (kind == null ? "Contract extractor evidence" : kind.toString()) + (symbol == null ? "" : ": " + symbol);
    }
    private static Map<String,Object> storedMetadata(Map<String,Object> original,String file,String symbol,String identityVersion) {
        var result=new LinkedHashMap<>(original);
        result.put("extractionConfidence",extractionConfidence(original));
        boolean lineKnown=original.get("sourceRange") instanceof Map<?,?> range&&range.get("startLine") instanceof Number||original.get("boundStartLine") instanceof Number;
        result.put("sourceLineKnown",lineKnown);
        result.put("evidenceComplete",original.get("evidenceType")!=null&&file!=null&&!file.isBlank()&&symbol!=null&&!symbol.isBlank()&&lineKnown);
        if(identityVersion!=null) result.put("contractIdentityVersion",identityVersion);
        return Map.copyOf(result);
    }
    private static double extractionConfidence(Map<String,Object> metadata) {
        Object type = metadata.get("evidenceType");
        if (type == null) return 0.60;
        return switch (type.toString()) {
            case "DETERMINISTIC" -> 1.0;
            case "BUILD_METADATA", "PACKAGE_METADATA" -> 0.95;
            case "AST_ANALYSIS", "STATIC_ANALYSIS", "CONTRACT_ANALYSIS" -> 0.85;
            case "RUNTIME_EVIDENCE" -> 0.90;
            case "SEMANTIC_INFERENCE" -> 0.60;
            case "LLM_INFERENCE" -> 0.40;
            default -> 0.50;
        };
    }
    private static String contentHash(String key, String hash) { return hash == null || hash.isBlank() ? sha256(key) : hash; }
    private static int line(Optional<com.github.javaparser.Position> p){return p.map(x->x.line).orElse(1);}
    private static String classHeader(ClassOrInterfaceDeclaration c){StringBuilder s=new StringBuilder("class ").append(c.getNameAsString());if(!c.getExtendedTypes().isEmpty())s.append(" extends ").append(c.getExtendedTypes());if(!c.getImplementedTypes().isEmpty())s.append(" implements ").append(c.getImplementedTypes());return s.toString();}
    private static List<Chunk> split(String text,int startLine,int maxChars){
        int limit=Math.max(512,maxChars),overlapChars=Math.min(300,limit/10);String[] lines=text.split("\\R",-1);List<Chunk> out=new ArrayList<>();List<String> block=new ArrayList<>();int blockStart=0,size=0;
        for(int i=0;i<lines.length;i++){
            String line=lines[i];
            if(line.length()>limit){if(!block.isEmpty()){out.add(new Chunk(String.join("\n",block),startLine+blockStart,startLine+i-1));block.clear();size=0;}int pos=0;while(pos<line.length()){int end=Math.min(line.length(),pos+limit);out.add(new Chunk(line.substring(pos,end),startLine+i,startLine+i));if(end==line.length())break;pos=Math.max(pos+1,end-overlapChars);}blockStart=i+1;continue;}
            if(size+line.length()+1>limit&&!block.isEmpty()){int carry=Math.min(10,block.size());out.add(new Chunk(String.join("\n",block),startLine+blockStart,startLine+blockStart+block.size()-1));List<String> tail=new ArrayList<>(block.subList(block.size()-carry,block.size()));blockStart=blockStart+block.size()-carry;block=tail;size=block.stream().mapToInt(String::length).sum()+Math.max(0,block.size()-1);}
            if(block.isEmpty())blockStart=i;block.add(line);size+=line.length()+1;
        }
        if(!block.isEmpty())out.add(new Chunk(String.join("\n",block),startLine+blockStart,startLine+blockStart+block.size()-1));
        return out.isEmpty()?List.of(new Chunk("",startLine,startLine)):List.copyOf(out);
    }
    private static String sha256(String s) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); } catch(Exception e) { throw new IllegalStateException(e); } }
    private static String vectorLiteral(float[] v){return "["+String.join(",",java.util.stream.IntStream.range(0,v.length).mapToObj(i->Float.toString(v[i])).toList())+"]";}
    private static String sqlDiagnosticChain(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<String> details = new ArrayList<>();
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            StringBuilder detail = new StringBuilder(current.getClass().getName());
            if (current instanceof SQLException sql) {
                detail.append("[SQLSTATE=").append(Objects.toString(sql.getSQLState(), "unknown"));
                if ("org.postgresql.util.PSQLException".equals(current.getClass().getName())) {
                    detail.append(", message=").append(Objects.toString(sql.getMessage(), "unknown"));
                    appendPostgresServerFields(detail, current);
                }
                detail.append(']');
                for (SQLException next = sql.getNextException(); next != null && seen.add(next); next = next.getNextException()) {
                    StringBuilder nextDetail = new StringBuilder(next.getClass().getName())
                            .append("[SQLSTATE=").append(Objects.toString(next.getSQLState(), "unknown"));
                    if ("org.postgresql.util.PSQLException".equals(next.getClass().getName())) {
                        nextDetail.append(", message=").append(Objects.toString(next.getMessage(), "unknown"));
                        appendPostgresServerFields(nextDetail, next);
                    }
                    details.add(nextDetail.append(']').toString());
                }
            }
            details.add(detail.toString());
        }
        return String.join(" <- ", details);
    }

    private static void appendPostgresServerFields(StringBuilder detail, Throwable exception) {
        try {
            Object serverError = exception.getClass().getMethod("getServerErrorMessage").invoke(exception);
            if (serverError == null) return;
            for (String field : List.of("schema", "table", "column", "constraint")) {
                Object value = serverError.getClass().getMethod("get" + Character.toUpperCase(field.charAt(0)) + field.substring(1)).invoke(serverError);
                if (value != null && !value.toString().isBlank()) detail.append(", ").append(field).append('=').append(value);
            }
        } catch (ReflectiveOperationException ignored) {
            // PostgreSQL server detail fields are optional across driver and server error types.
        }
    }
    private static String truncate(String s){if(s==null)return "indexing failed";return s.substring(0,Math.min(s.length(),1500));}
    private record Job(UUID repositoryId,String sha,int attempt){}
    private record Symbol(String type,String name,String qualified,int start,int end,String text){}
    private record Chunk(String text,int start,int end){}
}
