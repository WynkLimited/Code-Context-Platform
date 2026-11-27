package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AdminSecurityControllerTest {
    private final CodeContextProperties properties = new CodeContextProperties(
            "/repos", "/git", "/zoekt",
            "orders=current-secret|previous-secret",
            "ops=admin-secret",
            "webhook", "", "github.com", "http://localhost",
            1024, 1_048_576, 6000, 2, 300,
            new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
            new CodeContextProperties.Graphify("graphify", false));

    @Test void returnsTeamKeyCountsForAdminWithoutSecrets() {
        var controller = new AdminSecurityController(properties);
        var auth = new UsernamePasswordAuthenticationToken("ops", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_TEAM"), new SimpleGrantedAuthority("ROLE_ADMIN")));
        Map<String, Object> status = controller.apiKeyStatus(auth);
        assertEquals(1, status.get("teamCount"));
        assertEquals(Map.of("orders", 2), status.get("teamKeyCounts"));
        assertEquals(Map.of("ops", 1), status.get("adminKeyCounts"));
        assertFalse(status.toString().contains("secret"));
    }

    @Test void rejectsNonAdminCallers() {
        var controller = new AdminSecurityController(properties);
        var auth = new UsernamePasswordAuthenticationToken("orders", "n/a",
                List.of(new SimpleGrantedAuthority("ROLE_TEAM")));
        assertThrows(AccessDeniedException.class, () -> controller.apiKeyStatus(auth));
    }
}
