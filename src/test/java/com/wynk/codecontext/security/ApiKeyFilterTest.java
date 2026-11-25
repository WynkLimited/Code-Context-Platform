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
            6000, 2, 300, new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
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

    @Test void dualKeyRotationAcceptsCurrentAndPreviousDuringOverlap() throws Exception {
        var rotating = new CodeContextProperties(
                "/tmp/repos", "/tmp/fixtures", "/tmp/zoekt",
                "orders=new-team-secret|old-team-secret",
                "platform=new-admin-secret|old-admin-secret",
                "webhook-secret", "", "github.com", "http://localhost", 1024, 1024 * 1024,
                6000, 2, 300, new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify", false));

        for (String key : new String[]{"new-team-secret", "old-team-secret"}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-API-Key", key);
            new ApiKeyFilter(rotating).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication, key);
            assertEquals("orders", authentication.getPrincipal(), key);
            assertFalse(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")), key);
        }
        for (String key : new String[]{"new-admin-secret", "old-admin-secret"}) {
            SecurityContextHolder.clearContext();
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-API-Key", key);
            new ApiKeyFilter(rotating).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            assertNotNull(authentication, key);
            assertEquals("platform", authentication.getPrincipal(), key);
            assertTrue(authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")), key);
        }
    }

    @Test void droppingPreviousKeyRevokesItWithoutAcceptingUnknownKeys() throws Exception {
        var afterRotation = new CodeContextProperties(
                "/tmp/repos", "/tmp/fixtures", "/tmp/zoekt",
                "orders=new-team-secret",
                "platform=new-admin-secret",
                "webhook-secret", "", "github.com", "http://localhost", 1024, 1024 * 1024,
                6000, 2, 300, new CodeContextProperties.Zoekt("zoekt-git-index", "zoekt-local-sync", "http://localhost:6070"),
                new CodeContextProperties.Graphify("graphify", false));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-API-Key", "old-team-secret");
        new ApiKeyFilter(afterRotation).doFilter(request, new MockHttpServletResponse(), (req, res) -> { });
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
