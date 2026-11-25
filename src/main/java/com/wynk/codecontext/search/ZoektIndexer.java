package com.wynk.codecontext.search;

import tools.jackson.databind.json.JsonMapper;
import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
public class ZoektIndexer {
    private final CodeContextProperties config; private final JsonMapper json;
    public ZoektIndexer(CodeContextProperties c,JsonMapper m){config=c;json=m;}
    public void index(RepositoryRecord repository,Path checkout,java.util.UUID generation) throws Exception {
        String commit;
        try(var git=org.eclipse.jgit.api.Git.open(checkout.toFile())) {
            var head=git.getRepository().resolve("HEAD^{commit}");
            if(head==null)throw new IOException("Zoekt source commit is unavailable");
            commit=head.name();
        }
        Path index=Path.of(config.zoektIndexRoot()).toAbsolutePath().normalize();Files.createDirectories(index);
        Path metadata=index.resolve("meta-"+repository.id()+".json");
        String generationName=repository.key()+"@"+generation;
        json.writeValue(metadata.toFile(),new RepoMetadata(generationName,"Code Context indexed generation"));
        Path log=index.resolve("index-"+repository.id()+".log");
        Process process=new ProcessBuilder(List.of(config.zoekt().indexer(),"-index",index.toString(),"-branches",commit,"-incremental","-meta",metadata.toString(),checkout.toString()))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        boolean done=process.waitFor(Duration.ofMinutes(20).toMillis(),TimeUnit.MILLISECONDS);
        if(!done){process.destroyForcibly();throw new IOException("Zoekt indexing exceeded 20 minute limit");}
        String output=Files.exists(log)?Files.readString(log):"";
        if(process.exitValue()!=0)throw new IOException("Zoekt indexing failed with exit "+process.exitValue()+": "+output.substring(0,Math.min(1500,output.length()))+"; "+gitDiagnostics(checkout,commit));
    }

    private static String gitDiagnostics(Path checkout,String commit) {
        String refs;
        try(var git=org.eclipse.jgit.api.Git.open(checkout.toFile())) {
            refs="jgitRefDatabase="+git.getRepository().getRefDatabase().getClass().getSimpleName()+
                    "; refs="+git.getRepository().getRefDatabase().getRefsByPrefix("refs/heads/").stream().limit(10)
                    .map(r->r.getName()+"="+r.getObjectId().name()).toList()+
                    "; gitDir="+git.getRepository().getDirectory()+
                    "; repositoryFormat="+git.getRepository().getConfig().getString("core",null,"repositoryformatversion");
        }catch(Exception failure){refs="jgitDiagnostics="+failure.getClass().getSimpleName();}
        try {
            var process=new ProcessBuilder("git","-C",checkout.toString(),"cat-file","-t",commit).redirectErrorStream(true).start();
            if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();return refs+"; nativeGit=timeout";}
            return refs+"; nativeGit="+new String(process.getInputStream().readNBytes(1024),java.nio.charset.StandardCharsets.UTF_8).strip()+" exit="+process.exitValue();
        }catch(Exception failure){return refs+"; nativeGit="+failure.getClass().getSimpleName();}
    }

    /** Delete only explicit obsolete generation repositories after database publication succeeds. */
    public void removeGeneration(RepositoryRecord repository, java.util.UUID generation) throws Exception {
        Path index=Path.of(config.zoektIndexRoot()).toAbsolutePath().normalize();
        if(!Files.isDirectory(index))return;
        Path log=Files.createTempFile(index,"zoekt-remove-",".log");
        Process process=null;
        try {
            process=new ProcessBuilder(List.of(config.zoekt().cleaner(),"remove","-index",index.toString(),"-f",repository.key()+"@"+generation))
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if(!process.waitFor(Duration.ofSeconds(30).toMillis(),TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Zoekt obsolete-generation removal exceeded 30 second limit");
            }
            String output=Files.exists(log)?Files.readString(log):"";
            if(process.exitValue()!=0)throw new IOException("Zoekt obsolete-generation removal failed with exit "+process.exitValue()+": "+output.substring(0,Math.min(1000,output.length())));
        } finally {
            if(process!=null&&process.isAlive())process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
    private record RepoMetadata(String name,String description) {}
}
