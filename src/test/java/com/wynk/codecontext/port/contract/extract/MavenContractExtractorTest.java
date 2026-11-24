package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractEntryMetadata;
import com.wynk.codecontext.port.contract.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenContractExtractorTest {
    @Test
    void implicitPublicInterfaceMethodsRetainOwnerAndExcludePrivateHelpers() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/player"));
        Files.writeString(root.resolve("pom.xml"), "<project><groupId>test</groupId><artifactId>player</artifactId><version>1</version></project>");
        Files.writeString(root.resolve("src/main/java/player/IPlayer.java"), """
                package player;
                public interface IPlayer {
                    void pause();
                    default void play() {}
                    private void helper() {}
                    interface Listener { void onChange(); }
                }
                """);
        var signature = new MavenContractExtractor().extractExports(
                new RepositoryContext("o", "r", "a".repeat(40), "main", root)).get(0).metadata().get("signature").toString();
        assertTrue(signature.contains("player.IPlayer#public void pause();"));
        assertTrue(signature.contains("player.IPlayer.Listener#public void onChange();"));
        assertTrue(!signature.contains("helper"));
    }

    @TempDir
    Path root;

    @Test
    void extractsPublishedCoordinatesDependenciesAndPublicApi() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>in.wynk</groupId><artifactId>campaign-api</artifactId><version>2.4.0</version>
                  <dependencies><dependency>
                    <groupId>in.wynk</groupId><artifactId>shared-model</artifactId><version>3.1.0</version>
                  </dependency></dependencies>
                </project>
                """);
        Files.createDirectories(root.resolve("src/main/java/in/wynk"));
        Files.writeString(root.resolve("src/main/java/in/wynk/CampaignApi.java"), """
                package in.wynk;
                public interface CampaignApi { public String getCampaign(String id); }
                """);
        var repository = new RepositoryContext("o", "r", "a".repeat(40), "main", root);
        var extractor = new MavenContractExtractor();

        var exports = extractor.extractExports(repository);
        var imports = extractor.extractImports(repository);

        assertEquals("maven:in.wynk:campaign-api", exports.get(0).canonicalKey());
        assertEquals("2.4.0", exports.get(0).contract().version());
        assertTrue(exports.get(0).metadata().get("signature").toString().contains("getCampaign"));
        assertEquals("EXPORT", exports.get(0).metadata().get(ContractEntryMetadata.EXPORT_ROLE));
        assertEquals("BUILD_METADATA", exports.get(0).metadata().get(ContractEntryMetadata.EVIDENCE_TYPE));
        assertEquals("maven:in.wynk:shared-model", imports.get(0).canonicalKey());
        assertEquals("3.1.0", imports.get(0).dependencyVersion());
        assertEquals("IMPORT", imports.get(0).metadata().get(ContractEntryMetadata.IMPORT_ROLE));
        assertTrue(extractor.mayDefineContract("pom.xml"));
        assertTrue(extractor.mayDefineContract("src/main/java/in/wynk/CampaignApi.java"));
    }

    @Test
    void multiModuleArtifactsReceiveOnlyTheirOwnPublicApi() throws Exception {
        Files.createDirectories(root.resolve("a/src/main/java"));
        Files.createDirectories(root.resolve("b/src/main/java"));
        Files.writeString(root.resolve("a/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>in.wynk</groupId>"
                        + "<artifactId>a</artifactId><version>1</version></project>");
        Files.writeString(root.resolve("b/pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>in.wynk</groupId>"
                        + "<artifactId>b</artifactId><version>1</version></project>");
        Files.writeString(root.resolve("a/src/main/java/A.java"), "public interface A { void a(); }");
        Files.writeString(root.resolve("b/src/main/java/B.java"), "public interface B { void b(); }");
        var extractor = new MavenContractExtractor();
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        var byKey = extractor.extractExports(repo).stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.canonicalKey(), e -> e));

        assertTrue(byKey.get("maven:in.wynk:a").metadata().get("signature").toString().contains("A"));
        assertTrue(!byKey.get("maven:in.wynk:a").metadata().get("signature").toString().contains("B"));
        assertTrue(byKey.get("maven:in.wynk:b").metadata().get("signature").toString().contains("B"));
    }

    @Test
    void publicJavaMethodSignatureRetainsDeclaringTypeForCrossRepositoryIdentity() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/in/wynk/dto"));
        Files.writeString(root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>in.wynk</groupId>"
                        + "<artifactId>api</artifactId><version>1</version></project>");
        Files.writeString(root.resolve("src/main/java/in/wynk/dto/DateUtils.java"), """
                package in.wynk.dto;
                public class DateUtils {
                    public static long getStartOfDay(long time, String zone) { return time; }
                }
                """);
        var repository = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        String signature = new MavenContractExtractor().extractExports(repository).get(0)
                .metadata().get("signature").toString();

        assertTrue(signature.contains(
                "in.wynk.dto.DateUtils#public static long getStartOfDay(long time, String zone) {"));
    }
}
