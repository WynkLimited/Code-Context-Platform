package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ApiKeyFilterTest {
    private final CodeContextProperties properties = new CodeContextProperties(
            "/tmp/repos", "/tmp/fixtures", "/tmp/zoekt", "orders=team-secret", "platform=admin-secret",
            "webhook-secret", "", "github.com", "http://localhost", 1024, 1024 * 1024,
            6000, 2, 300, new CodeContextProperties.Zoekt("zoekt-git-index", "http://localhost:6070"),
            new CodeContextProperties.Graphify("graphify", false));

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test void teamKeyEstablishesOnlyTeamRoleAndScope() throws Exception {
        var authentication = authenticate("team-secret");

        assertEquals("orders", authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TEAM")));
        assertFalse(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test void adminOnlyKeyAuthenticatesWithAdminRoleAndConfiguredScope() throws Exception {
        var authentication = authenticate("admin-secret");

        assertEquals("platform", authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test void unknownKeyDoesNotCreateAuthentication() throws Exception {
        authenticate("unknown");

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private org.springframework.security.core.Authentication authenticate(String key) throws IOException, jakarta.servlet.ServletException {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", key);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> { };
        new ApiKeyFilter(properties).doFilter(request, response, chain);
        return SecurityContextHolder.getContext().getAuthentication();
    }
}
