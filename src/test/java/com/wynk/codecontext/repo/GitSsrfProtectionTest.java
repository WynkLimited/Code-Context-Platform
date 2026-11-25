package com.wynk.codecontext.repo;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.HttpConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GitSsrfProtectionTest {
    @TempDir Path temp;

    @Test void rejectsLoopbackAndMetadataResolutionBeforeNetwork() throws Exception {
        var props = properties("github.com");
        var loopback = new GitRepositoryManager(props, host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var metadata = new GitRepositoryManager(props, host -> new InetAddress[]{InetAddress.getByName("169.254.169.254")});
        var record = new RepositoryRecord(UUID.randomUUID(), "acme", "payments",
                "https://github.com/acme/payments.git", "team", "main", "SERVICE",
                temp.resolve("repos/acme/payments").toString());
        assertThrows(IllegalArgumentException.class, () -> loopback.sync(record));
        assertThrows(IllegalArgumentException.class, () -> metadata.sync(record));
    }

    @Test void rejectsDnsRebindingHostAllowlistedButLoopback() {
        var props = properties("evil.example");
        var manager = new GitRepositoryManager(props, host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var record = new RepositoryRecord(UUID.randomUUID(), "acme", "trap",
                "https://evil.example/acme/trap.git", "team", "main", "SERVICE",
                temp.resolve("repos/acme/trap").toString());
        assertThrows(IllegalArgumentException.class, () -> manager.sync(record));
    }

    @Test void disablesHttpRedirectsOnSyncedMirror() throws Exception {
        Path sources = Files.createDirectories(temp.resolve("sources"));
        Path source = Files.createDirectories(sources.resolve("sample"));
        try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            Files.writeString(source.resolve("README.md"), "fixture\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("test", "test@example.com").setCommitter("test", "test@example.com").call();
        }
        var props = new CodeContextProperties(temp.resolve("repos").toString(), sources.toString(), temp.resolve("zoekt").toString(),
                "team=secret", "team=admin-secret", "local-hook", "", "github.com", "http://localhost,http://127.0.0.1",
                1024, 1024 * 1024, 6000, 2, 300,
                new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify", false));
        var manager = new GitRepositoryManager(props);
        var record = new RepositoryRecord(UUID.randomUUID(), "team", "sample", source.toUri().toString(), "team", "main", "SERVICE",
                temp.resolve("repos/team/sample").toString());
        Path clone = manager.sync(record);
        try (Git git = Git.open(clone.toFile())) {
            assertEquals(HttpConfig.HttpRedirectMode.FALSE,
                    git.getRepository().getConfig().getEnum(
                            HttpConfig.HttpRedirectMode.values(),
                            HttpConfig.HTTP, null, HttpConfig.FOLLOW_REDIRECTS_KEY,
                            HttpConfig.HttpRedirectMode.TRUE));
            assertEquals(0, git.getRepository().getConfig().getInt(HttpConfig.HTTP, null, HttpConfig.MAX_REDIRECTS_KEY, 5));
        }
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void rejectsSymlinkedMirrorPath() throws Exception {
        Path sources = Files.createDirectories(temp.resolve("sources"));
        Path source = Files.createDirectories(sources.resolve("sample"));
        try (Git git = Git.init().setDirectory(source.toFile()).setInitialBranch("main").call()) {
            Files.writeString(source.resolve("README.md"), "fixture\n");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("fixture").setAuthor("test", "test@example.com").setCommitter("test", "test@example.com").call();
        }
        Path repos = Files.createDirectories(temp.resolve("repos"));
        Path team = Files.createDirectories(repos.resolve("team"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.createSymbolicLink(team.resolve("sample"), outside);
        var props = new CodeContextProperties(repos.toString(), sources.toString(), temp.resolve("zoekt").toString(),
                "team=secret", "team=admin", "hook", "", "github.com", "http://localhost",
                1024, 1024 * 1024, 6000, 2, 300,
                new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify", false));
        var manager = new GitRepositoryManager(props);
        var record = new RepositoryRecord(UUID.randomUUID(), "team", "sample", source.toUri().toString(), "team", "main", "SERVICE",
                team.resolve("sample").toString());
        var failure = assertThrows(Exception.class, () -> manager.sync(record));
        assertTrue(failure.getMessage().toLowerCase().contains("symlink"));
    }

    private CodeContextProperties properties(String allowedHost) {
        return new CodeContextProperties(temp.resolve("repos").toString(), temp.resolve("sources").toString(),
                temp.resolve("zoekt").toString(), "team=secret", "team=admin", "hook", "", allowedHost,
                "http://localhost", 1024, 1024 * 1024, 6000, 2, 300,
                new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify", false));
    }
}
