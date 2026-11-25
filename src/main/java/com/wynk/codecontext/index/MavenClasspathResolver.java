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
    private static final int MAX_POMS = 256;
    private static final int MAX_ARTIFACTS = 1000;
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
            for(Path pom:poms.stream().sorted().toList()) {
                Model model=effectiveModel(pom,reactorPoms,approvedRepositories);
                String module=root.relativize(pom.getParent()).toString().replace(File.separatorChar,'/');
                var repositories=approvedRepositories.stream().map(RepositorySpec::remote).toList();
                var session=(DefaultRepositorySystemSession)MavenRepositorySystemUtils.newSession();
                session.setLocalRepositoryManager(system.newLocalRepositoryManager(session,new LocalRepository(localRepository.toFile())));
                session.setConfigProperty("aether.connector.connectTimeout",10000);
                session.setConfigProperty("aether.connector.requestTimeout",30000);
                session.setConfigProperty("aether.transport.http.connectTimeout",10000);
                session.setConfigProperty("aether.transport.http.requestTimeout",30000);
                // Maven Resolver's HTTP transporter still follows redirects at the Apache HttpClient
                // layer; approved HTTPS hosts are DNS/private-address checked below, and cluster
                // NetworkPolicy must deny private egress as the remaining control.
                session.setConfigProperty("aether.artifactResolver.snapshotNormalization",false);
                var reactorCoordinates=new LinkedHashSet<String>();
                for(Path reactorPom:reactorPoms) { try { var m=effectiveModel(reactorPom,reactorPoms,approvedRepositories); reactorCoordinates.add(coord(m.getGroupId(),m.getArtifactId(),m.getVersion())); } catch(Exception ignored) { } }
                var collect=new CollectRequest(); collect.setRepositories(repositories);
                var dependencies=new ArrayList<org.eclipse.aether.graph.Dependency>();
                for(Dependency d:model.getDependencies()) {
                    String scope=normalizeScope(d.getScope());
                    if(scope.equals("test")||scope.equals("system")) continue;
                    if(reactorCoordinates.contains(coord(d.getGroupId(),d.getArtifactId(),d.getVersion()))) continue;
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
                for(Path jar:jars) if(Files.size(jar)>512L*1024*1024) return Result.incomplete(commit,"ARTIFACT_SIZE_LIMIT_EXCEEDED",modules);
                var artifactRows=new LinkedHashMap<String,ResolvedDependency>();
                collectDependencies(resolved.getRoot(),0,artifactRows);
                modules.add(new ModuleClasspath(module,model.getGroupId(),model.getArtifactId(),model.getVersion(),model.getPackaging(),List.copyOf(jars),fingerprint(jars),"RESOLVED",null,List.copyOf(artifactRows.values())));
            }
            return new Result(commit,List.copyOf(modules),true,null);
        } catch(Exception ex) { String detail=String.valueOf(ex.getMessage()).replaceAll("[\\r\\n]+"," ");return Result.incomplete(commit,"MAVEN_RESOLUTION_FAILED:"+ex.getClass().getSimpleName()+":"+detail.substring(0,Math.min(250,detail.length())),List.of()); }
    }

    private Model effectiveModel(Path pom,Set<Path> reactor,List<RepositorySpec> approved) throws ModelBuildingException {
        var request=new DefaultModelBuildingRequest().setPomFile(pom.toFile()).setProcessPlugins(false).setValidationLevel(0)
                .setModelResolver(new SnapshotModelResolver(reactor,approved));
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
                Path file=artifact.getFile().toPath();String checksum=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
                String scope=child.getDependency()==null?"compile":child.getDependency().getScope();boolean optional=child.getDependency()!=null&&child.getDependency().isOptional();
                var row=new ResolvedDependency(artifact.getGroupId(),artifact.getArtifactId(),artifact.getVersion(),artifact.getClassifier()==null?"":artifact.getClassifier(),scope,depth>0,optional,file,checksum);
                out.putIfAbsent(row.groupId()+":"+row.artifactId()+":"+row.version()+":"+row.classifier()+":"+scope,row);
            }
            collectDependencies(child,depth+1,out);
        }
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
    private static final class SnapshotModelResolver implements ModelResolver {
        private final Set<Path> reactor; private final List<RepositorySpec> approved;
        SnapshotModelResolver(Set<Path> reactor,List<RepositorySpec> approved){this.reactor=reactor;this.approved=approved;}
        private ModelSource resolve(String g,String a,String v) throws UnresolvableModelException {
            String rel=g.replace('.','/')+"/"+a+"/"+v+"/"+a+"-"+v+".pom";
            for(RepositorySpec spec:approved){
                try {var uri=java.net.URI.create(spec.url());if(uri.getScheme().equals("file")){Path f=Path.of(uri).resolve(rel).normalize();if(Files.isRegularFile(f))return new FileModelSource(f.toFile());}}
                catch(Exception ignored) { }
            }
            for(Path pom:reactor) if(pom.getFileName().toString().equals("pom.xml")) {
                try {String raw=Files.readString(pom);if(raw.contains("<artifactId>"+a+"</artifactId>")&&raw.contains("<version>"+v+"</version>"))return new FileModelSource(pom.toFile());}catch(IOException ignored){}
            }
            throw new UnresolvableModelException("Parent/BOM not available in exact snapshot or approved file repositories",g,a,v);
        }
        public ModelSource resolveModel(String g,String a,String v)throws UnresolvableModelException{return resolve(g,a,v);}
        public ModelSource resolveModel(org.apache.maven.model.Parent p)throws UnresolvableModelException{return resolve(p.getGroupId(),p.getArtifactId(),p.getVersion());}
        public ModelSource resolveModel(Dependency d)throws UnresolvableModelException{return resolve(d.getGroupId(),d.getArtifactId(),d.getVersion());}
        public void addRepository(Repository r)throws InvalidRepositoryException {addRepository(r,false);}
        public void addRepository(Repository r,boolean replace)throws InvalidRepositoryException {
            // Model building may announce Central from Maven's super-POM. Repository use is
            // controlled separately by the approved list passed to Resolver, so never accept POM URLs here.
        }
        public ModelResolver newCopy(){return new SnapshotModelResolver(reactor,approved);}
    }
}
