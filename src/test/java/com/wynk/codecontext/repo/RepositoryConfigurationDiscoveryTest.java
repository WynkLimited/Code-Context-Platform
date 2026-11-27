package com.wynk.codecontext.repo;

import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RepositoryConfigurationDiscoveryTest {
    @TempDir Path root;
    @Test void discoversOnlyRegularTrackedManifestsAtTheExactCommitWithoutApplyingRepositorySettings()throws Exception {
        try(var git=Git.init().setDirectory(root.toFile()).setInitialBranch("main").call()) {
            Files.writeString(root.resolve("pom.xml"),"<project/>\n");
            Files.createDirectories(root.resolve("nested/child"));Files.writeString(root.resolve("nested/child/pom.xml"),"<project/>\n");
            Files.createDirectories(root.resolve("build"));Files.writeString(root.resolve("build/pom.xml"),"<project/>\n");
            Files.writeString(root.resolve("nested/child/Example.java"),"class Example {}\n");
            Files.writeString(root.resolve(".code-context.yml"),"team: untrusted\ncommand: never-execute-this\n");
            Files.createSymbolicLink(root.resolve("package.json"),Path.of("../outside.json"));
            git.add().addFilepattern(".").call();String commit=git.commit().setMessage("tracked configuration").setAuthor("fixture","fixture@example.test").setCommitter("fixture","fixture@example.test").call().name();
            Files.writeString(root.resolve("settings.gradle"),"untracked settings\n");Files.delete(root.resolve("pom.xml"));
            var snapshot=RepositoryConfigurationDiscovery.inspect(root,commit);
            assertEquals(List.of(".code-context.yml","build/pom.xml","nested/child/pom.xml","pom.xml"),snapshot.manifests().stream().map(RepositoryConfigurationDiscovery.Manifest::path).toList());
            assertEquals(List.of("MAVEN"),snapshot.buildSystems());assertEquals(List.of("JAVA"),snapshot.languages());
            assertFalse(snapshot.treeTruncated());assertFalse(snapshot.manifestsTruncated());
            for(var file:snapshot.manifests()){assertTrue(file.bytes()>0);assertTrue(file.blobSha().matches("[0-9a-f]{40}"));}
            assertFalse(snapshot.toString().contains("untrusted"));assertFalse(snapshot.toString().contains("never-execute"));
            assertThrows(IllegalArgumentException.class,()->RepositoryConfigurationDiscovery.inspect(root,"HEAD"));
        }
    }
}
