package com.wynk.codecontext.security;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SubprocessHardeningTest {
    @Test void scrubsCredentialBearingVariablesButKeepsRuntimePath() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin");
        env.put("JAVA_HOME", "/opt/java");
        env.put("AWS_SECRET_ACCESS_KEY", "x");
        env.put("GITHUB_TOKEN", "y");
        env.put("TEAM_API_KEYS", "should-go");
        env.put("NORMAL_FLAG", "keep");
        SubprocessHardening.scrubEnvironment(env);
        assertEquals("/usr/bin", env.get("PATH"));
        assertEquals("/opt/java", env.get("JAVA_HOME"));
        assertEquals("keep", env.get("NORMAL_FLAG"));
        assertFalse(env.containsKey("AWS_SECRET_ACCESS_KEY"));
        assertFalse(env.containsKey("GITHUB_TOKEN"));
        assertFalse(env.containsKey("TEAM_API_KEYS"));
        assertFalse(SubprocessHardening.osSandboxAvailable());
    }
}
