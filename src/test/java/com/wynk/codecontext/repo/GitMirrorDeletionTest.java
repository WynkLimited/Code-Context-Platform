package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GitMirrorDeletionTest {
    @TempDir Path temp;

    @Test void deleteMirrorRemovesContainedTreeAndRefusesSymlinkEscape() throws Exception {
        Path repos=Files.createDirectories(temp.resolve("repos"));
        Path mirror=Files.createDirectories(repos.resolve("acme").resolve("payments"));
        Files.writeString(mirror.resolve("README"),"mirror");
        Path outside=Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("secret"),"no");
        var props=new CodeContextProperties(repos.toString(),temp.resolve("local").toString(),temp.resolve("zoekt").toString(),
                "t=t","a=a","","","github.com","http://localhost",1024,1024,6000,2,300,
                new CodeContextProperties.Zoekt("zoekt-git-index","zoekt-local-sync","http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify",false));
        var manager=new GitRepositoryManager(props);
        var record=new RepositoryRecord(UUID.randomUUID(),"acme","payments","https://github.com/acme/payments.git","platform","main","SERVICE",mirror.toString());
        manager.deleteMirror(record);
        assertFalse(Files.exists(mirror));
        assertTrue(Files.exists(outside.resolve("secret")));

        Path escape=Files.createDirectories(repos.resolve("evil"));
        Files.createSymbolicLink(escape.resolve("payments"),outside);
        var escapeRecord=new RepositoryRecord(UUID.randomUUID(),"evil","payments","https://github.com/evil/payments.git","platform","main","SERVICE",escape.resolve("payments").toString());
        assertThrows(Exception.class,()->manager.deleteMirror(escapeRecord));
        assertTrue(Files.exists(outside.resolve("secret")));
    }
}
