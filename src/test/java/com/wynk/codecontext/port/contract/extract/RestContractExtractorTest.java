package com.wynk.codecontext.port.contract.extract;

import com.wynk.codecontext.port.contract.ContractEntryMetadata;
import com.wynk.codecontext.port.contract.RepositoryContext;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestContractExtractorTest {

    @TempDir
    Path root;

    @Test
    void extractsJaxRsServersAndMicroProfileClients() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/CampaignResource.java"), """
                @Path("/campaigns")
                class CampaignResource {
                  @GET @Path("/{id}") Campaign get(String id) { return null; }
                }
                """);
        Files.writeString(root.resolve("src/main/java/CampaignClient.java"), """
                @RegisterRestClient
                @Path("/campaigns")
                interface CampaignClient {
                  @Path("/{id}") @GET Campaign get(String id);
                }
                """);
        var extractor = extractor();
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals("rest:GET:/campaigns/{id}", extractor.extractExports(repo).get(0).canonicalKey());
        assertEquals("rest:GET:/campaigns/{id}", extractor.extractImports(repo).get(0).canonicalKey());
        var export = extractor.extractExports(repo).get(0);
        assertEquals("EXPORT", export.metadata().get(ContractEntryMetadata.EXPORT_ROLE));
        assertTrue(export.metadata().containsKey(ContractEntryMetadata.STABLE_SYMBOL_ID));
        assertEquals("GET /campaigns/{id}", export.metadata().get(ContractEntryMetadata.RESOLVED_SIGNATURE));
    }

    @Test
    void parsesQuotedAndDifferentlyIndentedOpenApiPaths() throws Exception {
        Files.writeString(root.resolve("openapi.yaml"), """
                openapi: 3.0.3
                paths:
                    "/campaign/{id}":
                        get:
                            responses:
                                "200": { description: ok }
                """);
        var extractor = extractor();
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        var export = extractor.extractExports(repo).get(0);
        assertEquals("rest:GET:/campaign/{id}", export.canonicalKey());
        assertTrue(export.metadata().get("signature").toString().contains("responses"));
    }

    @Test
    void extractsGoAndPythonClientOperationsAsImports() throws Exception {
        Files.writeString(root.resolve("client.py"), "requests.get(\"https://catalog.internal/items/42\")");
        Files.writeString(root.resolve("client.go"), "http.Post(\"https://catalog.internal/orders\", \"application/json\", body)");
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(java.util.Set.of("rest:GET:/items/42", "rest:POST:/orders"),
                extractor().extractImports(repo).stream()
                        .map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void resolvesPlaceholderPathsAndJvmHttpClientWrappers() throws Exception {
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.writeString(root.resolve("src/main/resources/application.properties"), "campaign.base=/campaigns\n");
        Files.createDirectories(root.resolve("src/main/kotlin"));
        Files.writeString(root.resolve("src/main/kotlin/CampaignResource.kt"), """
                @RequestMapping("${campaign.base}")
                class CampaignResource { @GetMapping("/{id}") fun get() = Unit }
                """);
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/CampaignClient.java"), """
                class CampaignClient {
                  void load() { webClient.get().uri("/campaigns/{id}"); }
                  void remove() { restTemplate.delete("/campaigns/{id}"); }
                }
                """);
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(java.util.Set.of("rest:GET:/campaigns/{id}"), extractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertEquals(java.util.Set.of("rest:GET:/campaigns/{id}", "rest:DELETE:/campaigns/{id}"),
                extractor().extractImports(repo).stream()
                        .map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void resolvesJavaAndKotlinPathConstantsButSkipsDynamicAndUnknownValues() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/CampaignResource.java"), """
                class CampaignResource {
                  private static final String PATH = "/campaigns/{id}";
                  @GetMapping(PATH) Object get() { return null; }
                }
                """);
        Files.createDirectories(root.resolve("src/main/kotlin"));
        Files.writeString(root.resolve("src/main/kotlin/CampaignResource.kt"), """
                const val PATH = "/campaigns/{id}"
                @GetMapping(PATH) fun get() = Unit
                @GetMapping(UNKNOWN_PATH) fun unknown() = Unit
                """);
        Files.writeString(root.resolve("src/main/java/Client.java"), """
                class Client { void load(String id) { webClient.get().uri("/campaigns/" + id); } }
                """);
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(java.util.Set.of("rest:GET:/campaigns/{id}"), extractor().extractExports(repo)
                .stream().map(e -> e.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(extractor().extractImports(repo).isEmpty());
    }

    @Test
    void resolvesComposedJaxRsPathFromConstants() throws Exception {
        Files.createDirectories(root.resolve("src/main/java"));
        Files.writeString(root.resolve("src/main/java/CampaignResource.java"), """
                class CampaignResource {
                  static final String BASE = "/campaign";
                  static final String ID = "/{id}";
                  @GET @Path(BASE + ID) Campaign get(String id) { return null; }
                }
                """);
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);
        assertEquals("rest:GET:/campaign/{id}",
                extractor().extractExports(repo).get(0).canonicalKey());
    }

    @Test
    void extractsTypeScriptFetchAndDotNetHttpClientOperationsAsImports() throws Exception {
        Files.writeString(root.resolve("client.ts"), "fetch(\"https://catalog.internal/items\", { method: \"DELETE\" })");
        Files.writeString(root.resolve("Client.cs"), "await httpClient.PostAsync(\"/orders\", content);");
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(java.util.Set.of("rest:DELETE:/items", "rest:POST:/orders"),
                extractor().extractImports(repo).stream()
                        .map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void extractsRustReqwestOperationsAsImports() throws Exception {
        Files.writeString(root.resolve("client.rs"), "client.patch(\"https://catalog.internal/items/7\").send().await;");
        var repo = new RepositoryContext("o", "r", "a".repeat(40), "main", root);

        assertEquals(java.util.Set.of("rest:PATCH:/items/7"), extractor().extractImports(repo)
                .stream().map(i -> i.canonicalKey()).collect(java.util.stream.Collectors.toSet()));
    }

    private static RestContractExtractor extractor() {
        return new RestContractExtractor(JsonMapper.builder().build());
    }
}
