package com.wynk.codecontext.security;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Application-level hardening for untrusted-repository subprocesses (kotlinc, Graphify, Maven).
 *
 * <p>This is <strong>not</strong> an OS sandbox. cgroup/seccomp/network namespaces remain
 * BLOCKED pending host capability. Callers must still set timeouts, destroy descendants, and
 * rely on cluster NetworkPolicy for egress.
 */
public final class SubprocessHardening {
    private static final Set<String> STRIP_PREFIXES = Set.of(
            "AWS_", "AZURE_", "GCP_", "GOOGLE_", "KUBE", "DOCKER", "CI_", "GITHUB_",
            "NPM_", "PIP_", "GRADLE_USER_HOME", "MAVEN_OPTS");

    private SubprocessHardening() {}

    /**
     * Removes cloud/CI credential-bearing environment variables from a subprocess environment
     * while preserving PATH/JAVA_HOME/HOME required for the tool to start.
     */
    public static void scrubEnvironment(Map<String, String> environment) {
        if (environment == null) return;
        Iterator<Map.Entry<String, String>> it = environment.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, String> entry = it.next();
            String key = entry.getKey() == null ? "" : entry.getKey().toUpperCase(Locale.ROOT);
            if (key.contains("SECRET") || key.contains("TOKEN") || key.contains("PASSWORD")
                    || key.contains("CREDENTIAL") || key.contains("API_KEY")
                    || STRIP_PREFIXES.stream().anyMatch(key::startsWith)) {
                it.remove();
            }
        }
    }

    public static boolean osSandboxAvailable() {
        return false;
    }
}
