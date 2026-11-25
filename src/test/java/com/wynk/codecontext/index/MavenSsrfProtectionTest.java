package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MavenSsrfProtectionTest {
    @TempDir Path temp;

    @Test void allowsHttpsRepositoryOnRfc1918WhenHostAllowlisted() throws Exception {
        var spec = new MavenClasspathResolver.RepositorySpec(
                "corp", "https://repo.example.com/maven/", Set.of("repo.example.com"));
        assertNotNull(spec.remote(host -> new InetAddress[]{InetAddress.getByName("192.168.10.5")}));
    }

    @Test void rejectsHttpsRepositoryResolvingToMetadataOrLoopback() {
        var spec = new MavenClasspathResolver.RepositorySpec(
                "meta", "https://repo.example.com/maven/", Set.of("repo.example.com"));
        assertThrows(IllegalArgumentException.class, () ->
                spec.remote(host -> new InetAddress[]{InetAddress.getByName("169.254.169.254")}));
        assertThrows(IllegalArgumentException.class, () ->
                spec.remote(host -> new InetAddress[]{InetAddress.getByName("127.0.0.1")}));
    }

    @Test void rejectsUnapprovedHttpsHost() {
        var spec = new MavenClasspathResolver.RepositorySpec(
                "central", "https://repo1.maven.org/maven2/", Set.of("approved.example"));
        assertThrows(IllegalArgumentException.class, () ->
                spec.remote(host -> new InetAddress[]{InetAddress.getByName("1.1.1.1")}));
    }

    @Test void acceptsApprovedPublicHttpsHost() throws Exception {
        var spec = new MavenClasspathResolver.RepositorySpec(
                "central", "https://repo1.maven.org/maven2/", Set.of("repo1.maven.org"));
        assertNotNull(spec.remote(host -> new InetAddress[]{InetAddress.getByName("1.1.1.1")}));
    }

    @Test @DisabledOnOs(OS.WINDOWS)
    void ignoresSymlinkedPomOutsideSnapshot() throws Exception {
        Path snapshot = Files.createDirectories(temp.resolve("snapshot"));
        Path outside = Files.createDirectories(temp.resolve("outside"));
        Files.writeString(outside.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>evil</groupId><artifactId>escape</artifactId><version>1</version></project>
                """);
        Files.createSymbolicLink(snapshot.resolve("pom.xml"), outside.resolve("pom.xml"));
        Files.writeString(snapshot.resolve("README"), "no reactor pom inside snapshot");
        var result = new MavenClasspathResolver().resolve(snapshot, "commit-symlink", temp.resolve("cache"), List.of());
        assertTrue(result.modules().isEmpty(), result.reason());
    }
}
