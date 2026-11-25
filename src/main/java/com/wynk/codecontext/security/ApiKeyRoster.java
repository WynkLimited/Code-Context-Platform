package com.wynk.codecontext.security;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses {@code team=key} and dual-key rotation entries {@code team=current|previous}.
 * Multiple keys may map to the same team during a rotation overlap window; dropping the
 * previous segment from configuration revokes that key without logging secret material.
 */
public final class ApiKeyRoster {
    private final Map<String, String> keyToTeam;

    private ApiKeyRoster(Map<String, String> keyToTeam) {
        this.keyToTeam = Map.copyOf(keyToTeam);
    }

    public static ApiKeyRoster parse(String configured) {
        if (configured == null || configured.isBlank()) {
            return new ApiKeyRoster(Map.of());
        }
        Map<String, String> keyToTeam = new LinkedHashMap<>();
        for (String entry : configured.split(",")) {
            String trimmed = entry.strip();
            if (!trimmed.contains("=")) continue;
            String[] pair = trimmed.split("=", 2);
            if (pair.length != 2) continue;
            String team = pair[0].strip();
            if (team.isEmpty()) continue;
            for (String keySegment : pair[1].split("\\|")) {
                String key = keySegment.strip();
                if (key.isEmpty()) continue;
                keyToTeam.putIfAbsent(key, team);
            }
        }
        return new ApiKeyRoster(keyToTeam);
    }

    public Map<String, String> keyToTeam() {
        return keyToTeam;
    }

    public Set<String> teams() {
        return Set.copyOf(new LinkedHashSet<>(keyToTeam.values()));
    }

    /** Validates every key segment without returning secret material. */
    public static void requireProductionStrength(String configured, String label) {
        if (configured == null || configured.isBlank() || configured.contains("local-dev-token")) {
            throw new IllegalStateException(label + " must configure production team tokens");
        }
        for (String entry : configured.split(",")) {
            String trimmed = entry.strip();
            String[] pair = trimmed.split("=", 2);
            if (pair.length != 2 || pair[0].strip().isEmpty()) {
                throw new IllegalStateException("each " + label + " entry must be team=key or team=current|previous");
            }
            String[] keys = pair[1].split("\\|");
            if (keys.length == 0) {
                throw new IllegalStateException("each " + label + " entry must include at least one key");
            }
            for (String key : keys) {
                if (key.strip().length() < 32) {
                    throw new IllegalStateException("each production " + label + " key must contain at least 32 characters");
                }
            }
        }
    }

    @Override
    public String toString() {
        return "ApiKeyRoster{teams=" + teams() + ",keys=" + keyToTeam.size() + "}";
    }
}
