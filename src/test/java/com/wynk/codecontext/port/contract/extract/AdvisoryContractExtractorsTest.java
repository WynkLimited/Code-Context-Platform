package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.RepositoryContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvisoryContractExtractorsTest {

    @TempDir
    Path root;

    @Test
    void extractsEnumsConfigCacheDatabaseAndDeepLinksFromFrameworkDeclarations() throws Exception {
        Files.createDirectories(root.resolve("src/main/java/in/wynk"));
        Files.createDirectories(root.resolve("src/main/resources/navigation"));
        Files.writeString(root.resolve("src/main/java/in/wynk/Contracts.java"), """
                package in.wynk;
                public enum Realm { MUSIC, PODCAST }
                @ConfigProperty(name = "feature.campaign.enabled") String campaignEnabled;
                @CacheResult(cacheName = "campaign-by-id") Object campaign(String id) { return null; }
                @Table(name = "campaigns") class CampaignEntity {}
                static final String CAMPAIGN_DEEPLINK_FORMAT = "wynk://campaign/{id}";
                """);
        Files.writeString(root.resolve("src/main/resources/application.properties"),
                "feature.campaign.enabled=false\n");
        Files.writeString(root.resolve("src/main/resources/navigation/routes.xml"), """
                <navigation xmlns:app="http://schemas.android.com/apk/res-auto">
                  <deepLink app:uri="wynk://campaign/{id}" />
                </navigation>
                """);
        Files.writeString(root.resolve("src/main/java/in/wynk/Consumer.java"), """
                package in.wynk.consumer;
                import in.wynk.Realm;
                class Consumer { Realm[] realms() { return Realm.values(); } }
                """);
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(Set.of("enum:in.wynk.Realm"), keys(new EnumContractExtractor().extractExports(repo)));
        assertEquals(Set.of("enum:in.wynk.Realm"), importKeys(new EnumContractExtractor().extractImports(repo)));
        assertEquals(Set.of("config:feature.campaign.enabled"),
                keys(new ConfigContractExtractor().extractExports(repo)));
        assertEquals(Set.of("config:feature.campaign.enabled"),
                importKeys(new ConfigContractExtractor().extractImports(repo)));
        assertEquals(Set.of("cache:campaign-by-id"), keys(new CacheContractExtractor().extractExports(repo)));
        assertEquals(Set.of("cache:campaign-by-id"),
                importKeys(new CacheContractExtractor().extractImports(repo)));
        assertEquals(Set.of("database:campaigns"), keys(new DatabaseContractExtractor().extractExports(repo)));
        assertEquals(Set.of("database:campaigns"),
                importKeys(new DatabaseContractExtractor().extractImports(repo)));
        assertEquals(Set.of("string_format:wynk://campaign/{id}"),
                keys(new StringFormatContractExtractor().extractExports(repo)));
        assertEquals(Set.of("string_format:wynk://campaign/{id}"),
                importKeys(new StringFormatContractExtractor().extractImports(repo)));
        assertTrue(new StringFormatContractExtractor().mayDefineContract("AndroidManifest.xml"));
    }

    private static Set<String> keys(java.util.List<com.wynk.codecontext.port.contract.ContractModels.ContractExport> values) {
        return values.stream().map(v -> v.canonicalKey()).collect(Collectors.toSet());
    }

    private static Set<String> importKeys(java.util.List<com.wynk.codecontext.port.contract.ContractModels.ContractImport> values) {
        return values.stream().map(v -> v.canonicalKey()).collect(Collectors.toSet());
    }
}
