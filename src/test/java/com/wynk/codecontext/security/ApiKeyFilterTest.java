package com.wynk.codecontext.security;

import com.wynk.codecontext.config.CodeContextProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.DispatcherType;
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

        assertTrue(authentication.isAuthenticated());
        assertEquals("orders", authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_TEAM")));
        assertFalse(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test void adminOnlyKeyAuthenticatesWithAdminRoleAndConfiguredScope() throws Exception {
        var authentication = authenticate("admin-secret");

        assertTrue(authentication.isAuthenticated());
        assertEquals("platform", authentication.getPrincipal());
        assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test void unknownKeyDoesNotCreateAuthentication() throws Exception {
        authenticate("unknown");

        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test void asyncAndErrorDispatchesReauthenticateFromApiKey() throws Exception {
        for (DispatcherType dispatcherType : new DispatcherType[]{DispatcherType.ASYNC, DispatcherType.ERROR}) {
            MockHttpServletRequest request = request("team-secret", dispatcherType);
            MockHttpServletResponse response = new MockHttpServletResponse();
            var filter = new ApiKeyFilter(properties);
            final org.springframework.security.core.Authentication[] authentication = new org.springframework.security.core.Authentication[1];

            filter.doFilter(request, response, (req, res) -> authentication[0] = SecurityContextHolder.getContext().getAuthentication());

            assertNotNull(authentication[0], dispatcherType.toString());
            assertEquals("orders", authentication[0].getPrincipal(), dispatcherType.toString());
        }
    }

    @Test void asyncAndErrorDispatchesRejectInvalidApiKeys() throws Exception {
        for (DispatcherType dispatcherType : new DispatcherType[]{DispatcherType.ASYNC, DispatcherType.ERROR}) {
            MockHttpServletRequest request = request("invalid", dispatcherType);
            MockHttpServletResponse response = new MockHttpServletResponse();
            SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("stale", ""));
            final org.springframework.security.core.Authentication[] authentication = new org.springframework.security.core.Authentication[1];

            new ApiKeyFilter(properties).doFilter(request, response,
                    (req, res) -> authentication[0] = SecurityContextHolder.getContext().getAuthentication());

            assertNull(authentication[0], dispatcherType.toString());
        }
    }

    private static MockHttpServletRequest request(String key, DispatcherType dispatcherType) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setDispatcherType(dispatcherType);
        request.addHeader("X-API-Key", key);
        return request;
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
