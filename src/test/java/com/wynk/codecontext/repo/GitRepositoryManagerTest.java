package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GitRepositoryManagerTest {
    @TempDir Path temp;

    @Test void clonesOnlyLocalRootsAndReadsTheIndexedCommit() throws Exception {
        Path sources=Files.createDirectory(temp.resolve("sources"));Path source=Files.createDirectory(sources.resolve("sample"));
        try(Git git=Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            Files.createDirectories(source.resolve("src"));Files.writeString(source.resolve("src/Hello.java"),"class Hello { int run(){ return 1; } }\n");
            git.add().addFilepattern(".").call();git.commit().setMessage("fixture").setAuthor("test","test@example.com").setCommitter("test","test@example.com").call();
        }
        var props=new CodeContextProperties(temp.resolve("repos").toString(),sources.toString(),temp.resolve("zoekt").toString(),"team=secret","team=admin-secret","local-hook","","github.com","http://localhost,http://127.0.0.1",1024,1024*1024,6000,2,300,new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),new CodeContextProperties.Graphify("graphify",false));
        var manager=new GitRepositoryManager(props);var record=new RepositoryRecord(UUID.randomUUID(),"team","sample",source.toUri().toString(),"team","main","SERVICE",temp.resolve("repos/team/sample").toString());
        Path clone=manager.sync(record);String commit=manager.head(clone);
        var java=manager.filesAt(clone,commit).stream().filter(f->f.path().equals("src/Hello.java")).findFirst().orElseThrow();
        assertTrue(java.content().contains("return 1"));assertFalse(java.blobSha().isBlank());
        assertTrue(manager.readFileAtRevision(clone,commit,"src/Hello.java",1000).contains("return 1"));
        assertThrows(IllegalArgumentException.class,()->manager.readFileAtRevision(clone,commit,"../../etc/passwd",1000));
        try(Git altered=Git.open(clone.toFile())) {
            var config=altered.getRepository().getConfig();
            config.setString("remote","origin","url","https://127.0.0.1/private/repo");
            config.save();
        }
        var failure=assertThrows(java.io.IOException.class,()->manager.sync(record));
        assertEquals("mirror origin does not match registered repository",failure.getMessage());
    }

    @Test void springSelectsProductionConstructorWithTestResolverConstructorPresent() {
        var props=new CodeContextProperties(temp.resolve("repos").toString(),temp.resolve("sources").toString(),temp.resolve("zoekt").toString(),"team=secret","team=admin-secret","","","github.com","http://localhost",1024,1024*1024,6000,2,300,new CodeContextProperties.Zoekt("zoekt-git-index","zoekt-local-sync","http://localhost:6070"),new CodeContextProperties.Graphify("graphify",false));
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(CodeContextProperties.class,()->props);
            context.register(GitRepositoryManager.class);
            context.refresh();
            assertNotNull(context.getBean(GitRepositoryManager.class));
        }
    }

    @Test void discoversNonMainDefaultAndRejectsMissingBranchesWithoutCreatingAMirror() throws Exception {
        Path sources=Files.createDirectory(temp.resolve("discovery"));Path source=Files.createDirectory(sources.resolve("repo"));
        try(Git git=Git.init().setDirectory(source.toFile()).setInitialBranch("release/pilot").call()) {
            Files.writeString(source.resolve("README.md"),"fixture\n");git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("test","test@example.com").setCommitter("test","test@example.com").call();
        }
        var props=new CodeContextProperties(temp.resolve("mirrors").toString(),sources.toString(),temp.resolve("zoekt").toString(),
                "t=k","t=a","hook","","github.com","http://localhost",1024,1024,6000,2,30,
                new CodeContextProperties.Zoekt("index","clean","http://localhost"),new CodeContextProperties.Graphify("graphify",false));
        var manager=new GitRepositoryManager(props);
        assertEquals("release/pilot",manager.verifyAccessAndBranch(source.toUri().toString(),null));
        assertEquals("release/pilot",manager.verifyAccessAndBranch(source.toUri().toString(),"release/pilot"));
        assertThrows(IllegalArgumentException.class,()->manager.verifyAccessAndBranch(source.toUri().toString(),"main"));
        assertThrows(IllegalArgumentException.class,()->manager.verifyAccessAndBranch(source.toUri().toString(),"../bad"));
        assertFalse(Files.exists(temp.resolve("mirrors")),"read-only discovery must not publish a mirror");
    }
}
