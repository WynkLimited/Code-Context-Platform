package com.wynk.codecontext.index;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;

import com.wynk.codecontext.security.OutboundUrlGuard;
import com.wynk.codecontext.security.PathContainment;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Effective-model + Maven Resolver classpath construction for an immutable repository snapshot. */
public final class MavenClasspathResolver {
    public static final String VERSION = "maven-resolver-1.9.26-policy-3";
    private static final int MAX_POMS = 256;
    private static final int MAX_ARTIFACTS = 1000;
    private static final long MAX_ARTIFACT_BYTES = 512L*1024*1024;
    private final RepositorySystem system;

    public MavenClasspathResolver() { this(newRepositorySystem()); }
    MavenClasspathResolver(RepositorySystem system) { this.system = system; }

    private static RepositorySystem newRepositorySystem() {
        var locator=MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class,BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class,FileTransporterFactory.class);
        locator.addService(TransporterFactory.class,HttpTransporterFactory.class);
        return locator.getService(RepositorySystem.class);
    }

    public Result resolve(Path commitRoot, String commit, Path localRepository, List<RepositorySpec> approvedRepositories) {
        try {
            Path root=commitRoot.toRealPath();
            PathContainment.rejectSymlinkAncestors(root, root);
            List<Path> poms;
            // walkContained does not follow directory symlinks; explicit symlink leaves are skipped
            // so a malicious checkout cannot pull POMs from outside the immutable snapshot.
            try(var files=PathContainment.walkContained(root)) {
                poms=files.filter(p->p.getFileName().toString().equals("pom.xml"))
                        .filter(p->!PathContainment.isSymlink(p))
                        .limit(MAX_POMS+1).toList();
            }
            if(poms.size()>MAX_POMS) return Result.incomplete(commit,"POM_LIMIT_EXCEEDED",List.of());
            Set<Path> reactorPoms=Set.copyOf(poms); var modules=new ArrayList<ModuleClasspath>();
            // Build each effective model once per immutable snapshot, rather than rebuilding
            // the entire reactor for every module. Never retain models across commits/repositories.
            Path cache=policyCache(localRepository,approvedRepositories);
            var importedModels=new LinkedHashMap<String,ModelSource>();
            var modelBudget=new java.util.concurrent.atomic.AtomicInteger();
            var models=new LinkedHashMap<Path,Model>();
            var reactorCoordinates=new LinkedHashSet<String>();
            for(Path pom:poms.stream().sorted().toList()) {
                Model model=effectiveModel(pom,root,reactorPoms,approvedRepositories,cache,importedModels,modelBudget);
                requireCoordinate(model.getGroupId(),model.getArtifactId(),model.getVersion());
                models.put(pom,model);
                reactorCoordinates.add(coord(model.getGroupId(),model.getArtifactId(),model.getVersion()));
            }
            for(var entry:models.entrySet()) {
                Path pom=entry.getKey(); Model model=entry.getValue();
                String module=root.relativize(pom.getParent()).toString().replace(File.separatorChar,'/');
                var repositories=approvedRepositories.stream().map(RepositorySpec::remote).toList();
                var session=newSession(cache);
                var collect=new CollectRequest(); collect.setRepositories(repositories);
                var dependencies=new ArrayList<org.eclipse.aether.graph.Dependency>();
                for(Dependency d:model.getDependencies()) {
                    String scope=normalizeScope(d.getScope());
                    if(scope.equals("test")||scope.equals("system")) continue;
                    if(reactorCoordinates.contains(coord(d.getGroupId(),d.getArtifactId(),d.getVersion()))) continue;
                    requireCoordinate(d.getGroupId(),d.getArtifactId(),d.getVersion());
                    requireSegment(d.getType());if(d.getClassifier()!=null&&!d.getClassifier().isBlank())requireSegment(d.getClassifier());
                    var artifact=new DefaultArtifact(d.getGroupId(),d.getArtifactId(),d.getClassifier(),d.getType(),d.getVersion());
                    var exclusions=d.getExclusions().stream().map(e->new Exclusion(e.getGroupId(),e.getArtifactId(),"*","*")).toList();
                    dependencies.add(new org.eclipse.aether.graph.Dependency(artifact,scope,d.isOptional(),exclusions));
                }
                collect.setDependencies(dependencies);
                var resolved=system.resolveDependencies(session,new DependencyRequest(collect,null));
                var jars=resolved.getArtifactResults().stream().filter(a->a.getArtifact().getFile()!=null)
                        .map(a->a.getArtifact().getFile().toPath())
                        .filter(p->Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) && !PathContainment.isSymlink(p))
                        .distinct().toList();
                if(jars.size()>MAX_ARTIFACTS) return Result.incomplete(commit,"ARTIFACT_LIMIT_EXCEEDED",modules);
                for(Path jar:jars) if(Files.size(jar)>MAX_ARTIFACT_BYTES) return Result.incomplete(commit,"ARTIFACT_SIZE_LIMIT_EXCEEDED",modules);
                var artifactRows=new LinkedHashMap<String,ResolvedDependency>();
                collectDependencies(resolved.getRoot(),0,artifactRows);
                modules.add(new ModuleClasspath(module,model.getGroupId(),model.getArtifactId(),model.getVersion(),model.getPackaging(),List.copyOf(jars),fingerprint(jars),"RESOLVED",null,List.copyOf(artifactRows.values())));
            }
            return new Result(commit,List.copyOf(modules),true,null);
        } catch(Exception ex) { String detail=String.valueOf(ex.getMessage()).replaceAll("[\\r\\n]+"," ");return Result.incomplete(commit,"MAVEN_RESOLUTION_FAILED:"+ex.getClass().getSimpleName()+":"+detail.substring(0,Math.min(250,detail.length())),List.of()); }
    }

    DefaultRepositorySystemSession newSession(Path cache) {
        var session=(DefaultRepositorySystemSession)MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session,new LocalRepository(cache.toFile())));
        session.setIgnoreArtifactDescriptorRepositories(true);
        session.setChecksumPolicy(org.eclipse.aether.repository.RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        session.setSystemProperties(safeBuildProperties());
        session.setUserProperties(Map.of());
        session.setConfigProperty("aether.connector.connectTimeout",10000);
        session.setConfigProperty("aether.connector.requestTimeout",30000);
        session.setConfigProperty("aether.connector.http.followRedirects",false);
        session.setConfigProperty("aether.artifactResolver.snapshotNormalization",false);
        return session;
    }

    private static Map<String,String> safeBuildProperties() {
        var properties=new LinkedHashMap<String,String>();
        for(String key:List.of("java.version","java.specification.version","os.name","os.arch","os.version")) {
            String value=System.getProperty(key);if(value!=null)properties.put(key,value);
        }
        return Map.copyOf(properties);
    }

    /** Cache trust includes the ordered repository policy, not merely Maven's repository id. */
    private static Path policyCache(Path base,List<RepositorySpec> approved) throws Exception {
        var digest=MessageDigest.getInstance("SHA-256");
        digest.update("operator-repositories-only-policy-3".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        for(var repository:approved) {
            var uri=java.net.URI.create(repository.url());
            String location="file".equals(uri.getScheme())?Path.of(uri).toRealPath().toUri().toString():uri.normalize().toString();
            for(String field:List.of(repository.id(),location,String.join("\n",new java.util.TreeSet<>(repository.approvedHosts())))) {
                byte[] bytes=field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());digest.update(bytes);
            }
        }
        Path root=base.toAbsolutePath().normalize();
        Path scoped=root.resolve("policy-3-"+java.util.HexFormat.of().formatHex(digest.digest()));
        PathContainment.rejectSymlinkAncestors(root,scoped);
        return scoped;
    }

    private Model effectiveModel(Path pom,Path root,Set<Path> reactor,List<RepositorySpec> approved,Path cache,
                                 Map<String,ModelSource> importedModels,java.util.concurrent.atomic.AtomicInteger modelBudget) throws ModelBuildingException {
        var properties=new java.util.Properties();properties.putAll(safeBuildProperties());
        var request=new DefaultModelBuildingRequest().setPomFile(pom.toFile()).setSystemProperties(properties)
                .setModelSource(new ContainedModelSource(pom,root)).setProcessPlugins(false).setValidationLevel(0)
                .setModelResolver(new SnapshotModelResolver(root,reactor,approved,cache,importedModels,modelBudget));
        return new DefaultModelBuilderFactory().newInstance().build(request).getEffectiveModel();
    }
    private static String normalizeScope(String scope) { return scope==null||scope.isBlank()?"compile":scope.toLowerCase(Locale.ROOT); }
    private static String coord(String g,String a,String v) { return String.valueOf(g)+":"+String.valueOf(a)+":"+String.valueOf(v); }
    private static String fingerprint(List<Path> jars) throws Exception {
        var md=MessageDigest.getInstance("SHA-256");
        for(Path p:jars){md.update(p.getFileName().toString().getBytes());md.update(Long.toString(Files.size(p)).getBytes());}
        return java.util.HexFormat.of().formatHex(md.digest());
    }
    private static void collectDependencies(org.eclipse.aether.graph.DependencyNode node,int depth,Map<String,ResolvedDependency> out) throws Exception {
        for(var child:node.getChildren()) {
            var artifact=child.getArtifact();
            if(artifact!=null&&artifact.getFile()!=null) {
                Path file=artifact.getFile().toPath();String checksum=artifactChecksum(file);
                String scope=child.getDependency()==null?"compile":child.getDependency().getScope();boolean optional=child.getDependency()!=null&&child.getDependency().isOptional();
                var row=new ResolvedDependency(artifact.getGroupId(),artifact.getArtifactId(),artifact.getVersion(),artifact.getClassifier()==null?"":artifact.getClassifier(),scope,depth>0,optional,file,checksum);
                out.putIfAbsent(row.groupId()+":"+row.artifactId()+":"+row.version()+":"+row.classifier()+":"+scope,row);
            }
            collectDependencies(child,depth+1,out);
        }
    }
    static String artifactChecksum(Path file) throws Exception {
        if(Files.size(file)>MAX_ARTIFACT_BYTES)throw new IOException("Artifact size limit exceeded");
        var digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[64*1024];long total=0;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            int count;
            while((count=input.read(buffer))!=-1) {
                total+=count;
                if(total>MAX_ARTIFACT_BYTES)throw new IOException("Artifact size limit exceeded");
                digest.update(buffer,0,count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }
    public record RepositorySpec(String id,String url,Set<String> approvedHosts) {
        RemoteRepository remote(){
            return remote(java.net.InetAddress::getAllByName);
        }
        /** Visible for adversarial DNS/private-address tests. */
        RemoteRepository remote(OutboundUrlGuard.NameResolver resolver){
            var uri=java.net.URI.create(url);
            if(uri.getScheme()==null) throw new IllegalArgumentException("Maven repository is not approved: "+id);
            if(uri.getScheme().equals("file")) {
                try {
                    Path local=Path.of(uri).toRealPath();
                    if(!Files.isDirectory(local)) throw new IllegalArgumentException("Maven file repository must be a directory: "+id);
                } catch(IOException ex) {
                    throw new IllegalArgumentException("Maven file repository is not readable: "+id, ex);
                }
                return new RemoteRepository.Builder(id,"default",url).build();
            }
            if(!"https".equals(uri.getScheme())) throw new IllegalArgumentException("Maven repository is not approved: "+id);
            try {
                OutboundUrlGuard.requireAllowlistedHttps(url, approvedHosts, resolver);
            } catch(IllegalArgumentException ex) {
                throw new IllegalArgumentException("Maven repository is not approved: "+id, ex);
            }
            return new RemoteRepository.Builder(id,"default",url).build();
        }
    }
    /** Only operator-supplied repositories are ever offered to Resolver; POM repository URLs are ignored. */
    public static List<RepositorySpec> configuredRepositories() {
        String hostsRaw=System.getProperty("MAVEN_ALLOWED_HOSTS",System.getenv().getOrDefault("MAVEN_ALLOWED_HOSTS",""));
        Set<String> hosts=java.util.Arrays.stream(hostsRaw.split(",")).map(String::strip).filter(s->!s.isBlank()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        String configured=System.getProperty("MAVEN_ALLOWED_REPOSITORIES",System.getenv().getOrDefault("MAVEN_ALLOWED_REPOSITORIES",""));
        if(configured.isBlank())return List.of();
        var repositories=new ArrayList<RepositorySpec>();
        for(String item:configured.split(";")) {
            String[] pair=item.split("=",2);if(pair.length!=2||pair[0].isBlank())throw new IllegalArgumentException("MAVEN_ALLOWED_REPOSITORIES entries must be id=url");
            var spec=new RepositorySpec(pair[0].strip(),pair[1].strip(),hosts);spec.remote();repositories.add(spec);
            if(repositories.size()>16)throw new IllegalArgumentException("Maven repository policy exceeds limit");
        }
        return List.copyOf(repositories);
    }
    public record ResolvedDependency(String groupId,String artifactId,String version,String classifier,String scope,boolean transitive,boolean optional,Path artifact,String sha256) {}
    public record ModuleClasspath(String modulePath,String groupId,String artifactId,String version,String packaging,List<Path> jars,String fingerprint,String status,String reason,List<ResolvedDependency> dependencies) {}
    public record Result(String commit,List<ModuleClasspath> modules,boolean complete,String reason) {
        static Result incomplete(String commit,String reason,List<ModuleClasspath> modules){return new Result(commit,List.copyOf(modules),false,reason);}
    }
    private static void requireCoordinate(String group,String artifact,String version) {
        requireSegment(group);requireSegment(artifact);requireSegment(version);
        if(group.startsWith("."))throw new IllegalArgumentException("unsafe Maven group path");
    }
    private static void requireSegment(String value) {
        if(value==null||value.isBlank()||value.equals(".")||value.equals("..")||value.contains("${")||value.contains("/")||value.contains("\\")||value.indexOf(0)>=0)
            throw new IllegalArgumentException("unsafe Maven coordinate path");
    }

    /** Maven parent traversal must obey the same containment as the captured source snapshot. */
    private static final class ContainedModelSource extends FileModelSource {
        private static final int MAX_MODEL_BYTES=16*1024*1024;
        private final Path file,root;
        ContainedModelSource(Path file,Path root){super(file.toFile());this.file=file.toAbsolutePath().normalize();this.root=root.toAbsolutePath().normalize();}
        @Override public java.io.InputStream getInputStream() throws IOException {
            PathContainment.rejectSymlinkAncestors(root,file);
            byte[] bytes;
            try(var input=Files.newInputStream(file)){bytes=input.readNBytes(MAX_MODEL_BYTES+1);}
            if(bytes.length>MAX_MODEL_BYTES)throw new IOException("Maven model exceeds size limit");
            try {
                var factory=javax.xml.parsers.SAXParserFactory.newInstance();
                factory.setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING,true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
                factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
                factory.setXIncludeAware(false);
                factory.newSAXParser().parse(new java.io.ByteArrayInputStream(bytes),new org.xml.sax.helpers.DefaultHandler(){
                    private int depth;
                    @Override public void startElement(String uri,String local,String qName,org.xml.sax.Attributes attributes)throws org.xml.sax.SAXException {
                        if(++depth>128)throw new org.xml.sax.SAXException("Maven model exceeds nesting limit");
                    }
                    @Override public void endElement(String uri,String local,String qName){depth--;}
                });
            }catch(Exception rejected){throw new IOException("Maven model XML is not permitted",rejected);}
            return new java.io.ByteArrayInputStream(bytes);
        }
        @Override public org.apache.maven.model.building.ModelSource2 getRelatedSource(String relativePath) {
            Path related=file.getParent().resolve(relativePath).normalize();
            try {
                PathContainment.rejectSymlinkAncestors(root,related);
                if(Files.isDirectory(related,LinkOption.NOFOLLOW_LINKS))related=related.resolve("pom.xml");
                PathContainment.rejectSymlinkAncestors(root,related);
                return Files.isRegularFile(related,LinkOption.NOFOLLOW_LINKS)?new ContainedModelSource(related,root):null;
            }catch(IOException rejected){return null;}
        }
    }

    private final class SnapshotModelResolver implements ModelResolver {
        private final Path root,cache; private final Set<Path> reactor; private final List<RepositorySpec> approved;
        private final Map<String,ModelSource> importedModels;
        private final java.util.concurrent.atomic.AtomicInteger modelBudget;
        SnapshotModelResolver(Path root,Set<Path> reactor,List<RepositorySpec> approved,Path cache,
                              Map<String,ModelSource> importedModels,java.util.concurrent.atomic.AtomicInteger modelBudget){
            this.root=root;this.reactor=reactor;this.approved=approved;this.cache=cache;this.importedModels=importedModels;this.modelBudget=modelBudget;
        }
        private ModelSource resolve(String g,String a,String v) throws UnresolvableModelException {
            try { requireCoordinate(g,a,v); }
            catch(IllegalArgumentException invalid){throw new UnresolvableModelException("Unsafe parent/BOM coordinates",g,a,v);}
            String key=g+":"+a+":"+v;
            if(importedModels.containsKey(key))return importedModels.get(key);
            if(modelBudget.incrementAndGet()>MAX_POMS)throw new UnresolvableModelException("Imported model budget exceeded",g,a,v);
            String rel=g.replace('.','/')+"/"+a+"/"+v+"/"+a+"-"+v+".pom";
            for(RepositorySpec spec:approved){
                try {var uri=java.net.URI.create(spec.url());if(uri.getScheme().equals("file")){
                    Path approvedRoot=Path.of(uri).toRealPath();Path file=approvedRoot.resolve(rel).normalize();
                    PathContainment.rejectSymlinkAncestors(approvedRoot,file);
                    if(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)) {
                        ModelSource source=new ContainedModelSource(file,approvedRoot);importedModels.put(key,source);return source;
                    }
                }}
                catch(Exception ignored) { }
            }
            for(Path pom:reactor) if(pom.getFileName().toString().equals("pom.xml")) {
                try(var input=new ContainedModelSource(pom,root).getInputStream()) {
                    var model=new org.apache.maven.model.io.xpp3.MavenXpp3Reader().read(input);
                    String group=model.getGroupId()!=null?model.getGroupId():model.getParent()==null?null:model.getParent().getGroupId();
                    String version=model.getVersion()!=null?model.getVersion():model.getParent()==null?null:model.getParent().getVersion();
                    if(java.util.Objects.equals(g,group)&&java.util.Objects.equals(a,model.getArtifactId())&&java.util.Objects.equals(v,version))
                    { ModelSource source=new ContainedModelSource(pom,root);importedModels.put(key,source);return source; }
                }catch(Exception ignored){}
            }
            try {
                var remotes=approved.stream().filter(spec->"https".equals(java.net.URI.create(spec.url()).getScheme()))
                        .map(RepositorySpec::remote).toList();
                if(!remotes.isEmpty()) {
                    var artifact=new DefaultArtifact(g,a,"pom",v);
                    var request=new org.eclipse.aether.resolution.ArtifactRequest().setArtifact(artifact).setRepositories(remotes);
                    var resolved=system.resolveArtifact(newSession(cache),request);
                    Path file=resolved.getArtifact().getFile().toPath();PathContainment.rejectSymlinkAncestors(cache,file);
                    ModelSource source=new ContainedModelSource(file,cache);importedModels.put(key,source);return source;
                }
            }catch(Exception failed){throw new UnresolvableModelException("Parent/BOM unavailable from approved HTTPS repositories",g,a,v,failed);}
            throw new UnresolvableModelException("Parent/BOM not available in exact snapshot or approved repositories",g,a,v);
        }
        public ModelSource resolveModel(String g,String a,String v)throws UnresolvableModelException{return resolve(g,a,v);}
        public ModelSource resolveModel(org.apache.maven.model.Parent p)throws UnresolvableModelException{return resolve(p.getGroupId(),p.getArtifactId(),p.getVersion());}
        public ModelSource resolveModel(Dependency d)throws UnresolvableModelException{return resolve(d.getGroupId(),d.getArtifactId(),d.getVersion());}
        public void addRepository(Repository r)throws InvalidRepositoryException {addRepository(r,false);}
        public void addRepository(Repository r,boolean replace)throws InvalidRepositoryException {
            // Model building may announce Central from Maven's super-POM. Repository use is
            // controlled separately by the approved list passed to Resolver, so never accept POM URLs here.
        }
        public ModelResolver newCopy(){return new SnapshotModelResolver(root,reactor,approved,cache,importedModels,modelBudget);}
    }
}
