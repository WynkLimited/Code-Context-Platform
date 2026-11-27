package com.wynk.codecontext.index;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.repo.GitRepositoryManager.SourceFile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Isolated immutable generation inputs and trusted AST caches; no mutable workspace is shared by workers. */
@Service
public final class GraphifyWorkspaceStore {
    private static final long CACHE_LIMIT=1024L*1024*1024;
    private final CodeContextProperties config;private final JsonMapper json;
    public GraphifyWorkspaceStore(CodeContextProperties config,JsonMapper json){this.config=config;this.json=json;}
    public record Baseline(UUID generation,String commit) {}
    public record Workspace(UUID repository,UUID generation,String commit,long revision,Path root,Path sources,Path output,Path home,
                            Map<String,String> files,int reusedSources,int writtenSources,PublishedFileDelta delta,String mode) {}
    public record Marker(int format,UUID repository,UUID generation,String commit,long revision,String engine,
                         Map<String,String> files,String cacheDigest) {}
    public Workspace prepare(UUID repository,UUID generation,String commit,long revision,Baseline baseline,List<SourceFile> files)throws Exception {
        Path root=location(repository,generation,true);Files.createDirectory(root);
        Path sources=Files.createDirectory(root.resolve("sources")),output=root.resolve("engine-"+generation),home=Files.createDirectory(root.resolve("home"));
        Files.createDirectory(output);
        Marker prior=null;Path previous=null;
        if(baseline!=null) {
            previous=location(repository,baseline.generation(),false);
            try {
                Path marker=previous.resolve("state.json");
                if(Files.isSymbolicLink(marker)||Files.size(marker)>16L*1024*1024)throw new IOException("invalid state marker");
                prior=json.readValue(Files.readAllBytes(marker),Marker.class);
                Path cache=previous.resolve("engine-"+baseline.generation()).resolve("cache");
                if(prior.format()!=1||!repository.equals(prior.repository())||!baseline.generation().equals(prior.generation())
                        ||!baseline.commit().equals(prior.commit())||prior.revision()!=revision||!engine().equals(prior.engine())
                        ||!prior.cacheDigest().equals(digestTree(cache)))prior=null;
            }catch(IOException|RuntimeException invalid){prior=null;}
        }
        var current=new TreeMap<String,String>();for(var file:files)if(current.put(file.path(),file.blobSha())!=null)throw new IOException("duplicate source path");
        var delta=PublishedFileDelta.between(prior==null?Map.of():prior.files(),current);
        var moves=new HashMap<String,String>();for(var move:delta.renamed())moves.put(move.after(),move.before());
        int reused=0,written=0;
        for(var file:files) {
            Path target=contained(sources,file.path());Files.createDirectories(target.getParent());boolean linked=false;
            if(prior!=null) {
                String old=file.blobSha().equals(prior.files().get(file.path()))?file.path():moves.get(file.path());
                if(old!=null) {
                    Path from=contained(previous.resolve("sources"),old);
                    if(Files.isRegularFile(from,LinkOption.NOFOLLOW_LINKS)&&digestFile(from).equals(hash(file.content().getBytes(StandardCharsets.UTF_8)))) {
                        try {Files.createLink(target,from);linked=true;reused++;}
                        catch(UnsupportedOperationException|IOException unavailable){if(Files.exists(target))throw unavailable;}
                    }
                }
            }
            if(!linked){Files.writeString(target,file.content(),StandardOpenOption.CREATE_NEW);written++;}
            if(!target.toFile().setReadOnly())throw new IOException("cannot protect graph extraction input");
        }
        String mode=prior==null?"FULL_NO_VALID_BASELINE":delta.globalInputsChanged()?"FULL_GLOBAL_INPUT_CHANGE":"INCREMENTAL_AST_GLOBAL_RELINK";
        if(prior!=null&&!delta.globalInputsChanged())copyTree(previous.resolve("engine-"+baseline.generation()).resolve("cache"),output.resolve("cache"));
        return new Workspace(repository,generation,commit,revision,root,sources,output,home,Map.copyOf(current),reused,written,delta,mode);
    }
    public void complete(Workspace workspace)throws Exception {
        Path cache=workspace.output().resolve("cache");
        if(!Files.isDirectory(cache,LinkOption.NOFOLLOW_LINKS))return;
        var marker=new Marker(1,workspace.repository(),workspace.generation(),workspace.commit(),workspace.revision(),engine(),workspace.files(),digestTree(cache));
        Files.writeString(workspace.root().resolve("state.json"),json.writeValueAsString(marker),StandardOpenOption.CREATE_NEW);
    }
    public void remove(UUID repository,UUID generation)throws IOException {
        Path path=location(repository,generation,false);if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(path)){for(Path item:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(item);}
    }
    private Path location(UUID repository,UUID generation,boolean create)throws IOException {
        Path base=Path.of(config.repositoryRoot()).toAbsolutePath().normalize();
        if(create)Files.createDirectories(base);
        if(Files.exists(base))base=base.toRealPath();
        Path state=base.resolve(".graphify-state"),owner=state.resolve(repository.toString()),result=owner.resolve(generation.toString());
        for(Path component:List.of(state,owner,result))if(Files.isSymbolicLink(component))throw new IOException("symbolic link in graph state storage");
        if(create)Files.createDirectories(owner);return result;
    }
    private String engine(){return "ast-cache-1|"+com.wynk.codecontext.port.contract.ContractModels.GENERATOR_VERSION+"|"+config.graphify().command();}
    private static Path contained(Path root,String relative)throws IOException {
        Path path=Path.of(relative);
        if(path.isAbsolute()||!path.normalize().equals(path)||path.startsWith("..")||relative.indexOf('\0')>=0)throw new IOException("unsafe graph source path");
        Path result=root.resolve(path).normalize();if(!result.startsWith(root))throw new IOException("source escaped graph workspace");return result;
    }
    private static void copyTree(Path from,Path to)throws IOException {
        try(var paths=Files.walk(from)) {
            for(Path path:boundedPaths(paths)) {
                if(Files.isSymbolicLink(path))throw new IOException("symbolic link in AST cache");
                Path target=to.resolve(from.relativize(path));
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))Files.createDirectories(target);
                else if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))Files.copy(path,target,StandardCopyOption.COPY_ATTRIBUTES);
                else throw new IOException("unsupported AST cache entry");
            }
        }
    }
    private static String digestTree(Path root)throws IOException {
        if(!Files.isDirectory(root,LinkOption.NOFOLLOW_LINKS))throw new IOException("missing AST cache");
        var digest=sha256();long bytes=0;
        try(var paths=Files.walk(root)) {
            for(Path path:boundedPaths(paths)) {
                if(Files.isSymbolicLink(path))throw new IOException("symbolic link in AST cache");
                if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS))continue;
                if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("unsupported AST cache entry");
                bytes+=Files.size(path);if(bytes>CACHE_LIMIT)throw new IOException("AST cache exceeds storage budget");
                digest.update(root.relativize(path).toString().getBytes(StandardCharsets.UTF_8));digest.update((byte)0);
                digest.update(java.nio.ByteBuffer.allocate(8).putLong(Files.size(path)).array());feed(digest,path);
            }
        }return HexFormat.of().formatHex(digest.digest());
    }
    private static List<Path> boundedPaths(java.util.stream.Stream<Path> paths)throws IOException {
        var entries=paths.limit(100001).sorted().toList();
        if(entries.size()>100000)throw new IOException("AST cache exceeds entry budget");
        return entries;
    }
    private static String digestFile(Path path)throws IOException{var digest=sha256();feed(digest,path);return HexFormat.of().formatHex(digest.digest());}
    private static String hash(byte[] bytes){return HexFormat.of().formatHex(sha256().digest(bytes));}
    private static MessageDigest sha256(){try{return MessageDigest.getInstance("SHA-256");}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
    private static void feed(MessageDigest digest,Path path)throws IOException{try(var input=Files.newInputStream(path)){byte[] buffer=new byte[8192];for(int count;(count=input.read(buffer))!=-1;)digest.update(buffer,0,count);}}
}
