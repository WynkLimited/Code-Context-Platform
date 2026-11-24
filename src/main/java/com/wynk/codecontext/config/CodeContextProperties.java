package com.wynk.codecontext.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("code-context")
public record CodeContextProperties(String repositoryRoot, String localGitRoot, String zoektIndexRoot, String apiKeys, String adminApiKeys,
                                    String webhookSecret, String gitAccessToken, String gitAllowedHosts, String mcpAllowedOrigins, int embeddingDimensions,
                                    long maxFileBytes, int maxChunkChars, int maxIndexConcurrency, int gitTimeoutSeconds,
                                    Zoekt zoekt, Graphify graphify) {
    public record Zoekt(String indexer, String webserver) {}
    public record Graphify(String command, boolean enabled) {}
}
