package com.wynk.codecontext.search;

import com.wynk.codecontext.config.CodeContextProperties;
import com.wynk.codecontext.model.RepositoryRecord;
import com.wynk.codecontext.ops.PlatformMetrics;
import com.wynk.codecontext.repo.RepositoryStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.time.Duration;

@Service
public class ZoektSearchService {
    private static final int MAX_QUERY_LENGTH = 512;
    private static final int MAX_REPOSITORIES_PER_QUERY = 100;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

    private final RestClient zoekt;
    private final RepositoryStore repositories;
    private final JsonMapper json;
    private final PlatformMetrics metrics;

    @org.springframework.beans.factory.annotation.Autowired
    public ZoektSearchService(CodeContextProperties props, RepositoryStore repositories, JsonMapper json,
                              PlatformMetrics metrics) {
        this(props, repositories, json, defaultBuilder(), metrics);
    }

    public ZoektSearchService(CodeContextProperties props, RepositoryStore repositories, JsonMapper json) {
        this(props, repositories, json, defaultBuilder(), new PlatformMetrics(new SimpleMeterRegistry()));
    }

    ZoektSearchService(CodeContextProperties props, RepositoryStore repositories, JsonMapper json,
                       RestClient.Builder builder) {
        this(props, repositories, json, builder, new PlatformMetrics(new SimpleMeterRegistry()));
    }

    ZoektSearchService(CodeContextProperties props, RepositoryStore repositories, JsonMapper json,
                       RestClient.Builder builder, PlatformMetrics metrics) {
        this.zoekt = builder.baseUrl(props.zoekt().webserver()).build();
        this.repositories = repositories;
        this.json = json;
        this.metrics = metrics;
    }

    public String search(String query) {
        metrics.searchRequest();
        validateQuery(query);
        String team = authenticatedTeam();
        List<RepositoryRecord> allowed = repositories.findAccessible(team);
        if (allowed.size() > MAX_REPOSITORIES_PER_QUERY) {
            throw new IllegalStateException("search scope exceeds the configured repository limit");
        }

        var root = json.createObjectNode();
        var results = root.putArray("results");
        var errors = root.putArray("errors");
        for (RepositoryRecord repo : allowed) {
            var generation = repositories.currentGeneration(repo.id());
            var commit = repositories.currentCommit(repo.id());
            if (generation.isEmpty() || commit.isEmpty()) continue;

            String generationName = repo.key() + "@" + generation.get();
            String restrictedQuery = "(" + query + ") repo:^" + regexLiteral(generationName) + "$";
            try {
                var request = java.util.Map.of("Q", restrictedQuery, "Opts", java.util.Map.of(
                        "MaxDocDisplayCount", 50, "MaxMatchDisplayCount", 200,
                        "NumContextLines", 2, "MaxWallTime", 5_000_000_000L));
                JsonNode response = zoekt.post().uri("/api/search").contentType(MediaType.APPLICATION_JSON)
                        .body(request).exchange((httpRequest, httpResponse) -> {
                            if (httpResponse.getStatusCode().isError()) {
                                throw new IllegalStateException("Zoekt returned HTTP " + httpResponse.getStatusCode().value());
                            }
                            try (var body = httpResponse.getBody()) {
                                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                                if (bytes.length > MAX_RESPONSE_BYTES) {
                                    throw new IllegalStateException("Zoekt response exceeded the 2 MiB limit");
                                }
                                JsonNode result = bytes.length == 0 ? json.createObjectNode() : json.readTree(bytes).path("Result");
                                if(!result.isObject())throw new IllegalStateException("Zoekt response is missing Result object");
                                return result;
                            }
                        });
                var entry = results.addObject();
                entry.put("repository", repo.key());
                entry.put("commit", commit.get());
                entry.set("matches", response);
            } catch (Exception failure) {
                var entry = errors.addObject();
                entry.put("repository", repo.key());
                entry.put("commit", commit.get());
                entry.put("status", "UNAVAILABLE");
            }
        }
        root.put("status", errors.isEmpty() ? "COMPLETE" : results.isEmpty() ? "FAILED" : "PARTIAL");
        return json.writeValueAsString(root);
    }

    private static String authenticatedTeam() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || authentication.getPrincipal() == null) {
            throw new SecurityException("authenticated team is required for code search");
        }
        return String.valueOf(authentication.getPrincipal());
    }

    private static void validateQuery(String query) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("search query is required");
        if (query.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("search query exceeds 512 characters");
        if (query.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("search query must not contain control characters");
        }
        // User syntax must remain inside the group to which the authorized repo filter is added.
        int depth=0;boolean quoted=false;
        for(int i=0;i<query.length();i++) {
            char c=query.charAt(i);
            if(c=='\\') {
                if(++i==query.length())throw new IllegalArgumentException("search query has a trailing escape");
                continue;
            }
            if(c=='"'){quoted=!quoted;continue;}
            if(!quoted) {
                if(c=='(')depth++;
                if(c==')'&&--depth<0)throw new IllegalArgumentException("search query has unbalanced groups");
            }
        }
        if(quoted||depth!=0)throw new IllegalArgumentException("search query has unbalanced quotes or groups");
    }

    private static String regexLiteral(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\.+*?()|[]{}^$".indexOf(c) >= 0) escaped.append('\\');
            escaped.append(c);
        }
        return escaped.toString();
    }

    private static RestClient.Builder defaultBuilder() {
        var client = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        var requestFactory = new JdkClientHttpRequestFactory(client);
        requestFactory.setReadTimeout(Duration.ofSeconds(8));
        return RestClient.builder().requestFactory(requestFactory);
    }
}
