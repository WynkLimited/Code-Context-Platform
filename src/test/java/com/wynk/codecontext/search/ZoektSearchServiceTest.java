package com.wynk.codecontext.search;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.repo.RepositoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ZoektSearchServiceTest {
    private static final UUID REPOSITORY_ID = UUID.fromString("fdf66a02-11aa-4f0f-a045-fdd03e435462");
    private static final UUID GENERATION_ID = UUID.fromString("4656320c-11c6-4a55-9af6-39ff7de69c44");
    private static final String COMMIT = "0123456789012345678901234567890123456789";

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void scopesQueryToAccessiblePublishedGenerationAndIncludesCommitEvidence() throws Exception {
        var builder = RestClient.builder().baseUrl("http://localhost:6070");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> {
            assertEquals("POST", request.getMethod().name());
            assertEquals("/api/search", request.getURI().getPath());
            var requestBody = JsonMapper.builder().build().readTree(((MockClientHttpRequest) request).getBodyAsBytes());
            String query = requestBody.path("Q").asText();
            assertTrue(query.contains("repo:^fixture/common\\.library@" + GENERATION_ID + "$"), query);
            assertEquals(50, requestBody.path("Opts").path("MaxDocDisplayCount").asInt());
            assertEquals(200, requestBody.path("Opts").path("MaxMatchDisplayCount").asInt());
        }).andRespond(withSuccess("{\"Files\":[{\"FileName\":\"src/Main.java\"}]}", MediaType.APPLICATION_JSON));

        var service = new ZoektSearchService(properties(), repositories(), JsonMapper.builder().build(), builder);
        authenticate("team-a");
        var response = JsonMapper.builder().build().readTree(service.search("refundPolicy"));

        assertEquals("COMPLETE", response.path("status").asText());
        assertEquals(1, response.path("results").size());
        assertEquals("fixture/common.library", response.path("results").get(0).path("repository").asText());
        assertEquals(COMMIT, response.path("results").get(0).path("commit").asText());
        assertEquals("src/Main.java", response.path("results").get(0).path("matches").path("Files").get(0).path("FileName").asText());
        assertTrue(response.path("errors").isEmpty());
        server.verify();
    }

    @Test
    void requiresAuthenticatedTeamAndBoundsQueryInput() {
        var service = new ZoektSearchService(properties(), repositories(), JsonMapper.builder().build());
        SecurityContextHolder.clearContext();
        assertThrows(SecurityException.class, () -> service.search("anything"));
        authenticate("team-a");
        assertThrows(IllegalArgumentException.class, () -> service.search(" "));
        assertThrows(IllegalArgumentException.class, () -> service.search("x".repeat(513)));
        assertThrows(IllegalArgumentException.class, () -> service.search("bad\nquery"));
    }

    @Test
    void returnsNoRepositoryDataForOtherTeams() throws Exception {
        var builder = RestClient.builder().baseUrl("http://localhost:6070");
        var server = MockRestServiceServer.bindTo(builder).build();
        var service = new ZoektSearchService(properties(), repositories(), JsonMapper.builder().build(), builder);
        authenticate("team-b");

        var response = JsonMapper.builder().build().readTree(service.search("Money"));

        assertEquals("COMPLETE", response.path("status").asText());
        assertTrue(response.path("results").isEmpty());
        assertTrue(response.path("errors").isEmpty());
        server.verify();
    }

    @Test
    void limitsZoektResponseBytesAndReturnsPartialStatusWithoutExposingException() throws Exception {
        var builder = RestClient.builder().baseUrl("http://localhost:6070");
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(request -> assertEquals("POST", request.getMethod().name()))
                .andRespond(withSuccess("x".repeat(2 * 1024 * 1024 + 1), MediaType.APPLICATION_JSON));
        var service = new ZoektSearchService(properties(), repositories(), JsonMapper.builder().build(), builder);
        authenticate("team-a");

        var response = JsonMapper.builder().build().readTree(service.search("Money"));

        assertEquals("FAILED", response.path("status").asText());
        assertTrue(response.path("results").isEmpty());
        assertEquals("UNAVAILABLE", response.path("errors").get(0).path("status").asText());
        assertTrue(response.toString().length() < 1000, "service exception details must not be returned to the caller");
        server.verify();
    }

    @Test
    void springSelectsProductionConstructorWithTestTransportConstructorPresent() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(CodeContextProperties.class,ZoektSearchServiceTest::properties);
            context.registerBean(RepositoryStore.class,ZoektSearchServiceTest::repositories);
            context.registerBean(JsonMapper.class,()->JsonMapper.builder().build());
            context.register(ZoektSearchService.class);
            context.refresh();
            org.junit.jupiter.api.Assertions.assertNotNull(context.getBean(ZoektSearchService.class));
        }
    }

    private static void authenticate(String team) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(team, "", List.of()));
    }

    private static CodeContextProperties properties() {
        return new CodeContextProperties("/repos", "/fixtures", "/zoekt", "", "", "", "", "github.com",
                "https://mcp.example", 1024, 1_048_576, 6000, 2, 300,
                new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify", false));
    }

    private static RepositoryStore repositories() {
        RepositoryRecord allowed = new RepositoryRecord(REPOSITORY_ID, "fixture", "common.library", "", "team-a",
                "main", "LIBRARY", "/repos/fixture/common.library");
        return new RepositoryStore(new JdbcTemplate()) {
            @Override public List<RepositoryRecord> findAccessible(String team) {
                return "team-a".equals(team) ? List.of(allowed) : List.of();
            }
            @Override public Optional<UUID> currentGeneration(UUID repositoryId) {
                assertEquals(REPOSITORY_ID, repositoryId);
                return Optional.of(GENERATION_ID);
            }
            @Override public Optional<String> currentCommit(UUID repositoryId) {
                assertEquals(REPOSITORY_ID, repositoryId);
                return Optional.of(COMMIT);
            }
        };
    }
}
