package com.wynk.codecontext.port.contract;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContractIndexBuilderGradleGapTest {
    @TempDir Path root;

    @Test void recordsExplicitCoverageGapForGradleOnlyRepositories() throws Exception {
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { java }\n");
        Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\n");
        var builder = new ContractIndexBuilder(List.of());
        var index = builder.build(new RepositoryContext("o", "r", "a".repeat(40), "main", root));
        assertTrue(index.extractorVersions().getOrDefault("gradle-dependencies", "")
                .startsWith("unavailable:"), index.extractorVersions().toString());
    }

    @Test void doesNotFlagGradleGapWhenMavenPomIsPresent() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        Files.writeString(root.resolve("build.gradle"), "plugins { id 'java' }\n");
        var builder = new ContractIndexBuilder(List.of());
        var index = builder.build(new RepositoryContext("o", "r", "b".repeat(40), "main", root));
        assertFalse(index.extractorVersions().containsKey("gradle-dependencies"),
                index.extractorVersions().toString());
    }
}
