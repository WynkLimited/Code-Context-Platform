package com.wynk.codecontext.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitGateTest {
    @Test void disabledGateAlwaysAllows() {
        var gate = RateLimitGate.disabled();
        assertFalse(gate.enabled());
        for (int i = 0; i < 100; i++) {
            assertTrue(gate.check("team").allowed());
        }
    }

    @Test void fixedWindowRejectsAfterBudgetExhausted() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC);
        var gate = RateLimitGate.perMinute(2, clock);
        assertTrue(gate.check("orders").allowed());
        assertTrue(gate.check("orders").allowed());
        var denied = gate.check("orders");
        assertFalse(denied.allowed());
        assertTrue(denied.retryAfterMillis() > 0);
        assertTrue(gate.check("payments").allowed());
    }

    @Test void filterReturns429AndRetryAfterWhenEnabled() throws Exception {
        var gate = RateLimitGate.perMinute(1, Clock.fixed(Instant.parse("2026-11-25T08:00:00Z"), ZoneOffset.UTC));
        var filter = new RateLimitFilter(gate);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("orders", ""));
        try {
            MockHttpServletResponse first = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/repositories"), first, (req, res) -> {});
            assertEquals(200, first.getStatus());

            MockHttpServletResponse second = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/repositories"), second, (req, res) -> fail("should not continue"));
            assertEquals(429, second.getStatus());
            assertNotNull(second.getHeader("Retry-After"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test void sharedStoreFailureRejectsWithoutLeakingDetails() throws Exception {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate() {
            @Override public <T> T queryForObject(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
                throw new org.springframework.dao.DataAccessResourceFailureException("sensitive JDBC credentials");
            }
        };
        var filter = new RateLimitFilter(RateLimitGate.postgres(1, jdbc));
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/mcp"), response, (req, res) -> fail("storage failure must not bypass the budget"));
        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertEquals("request budget temporarily unavailable", response.getContentAsString());
    }

    @Test void backendConfigurationRejectsTyposAndDisabledSharedStoreNeedsNoDatabase() {
        var config = new SecurityConfig();
        assertThrows(IllegalArgumentException.class, () -> config.rateLimitGate(1, "postgre", null));
        assertFalse(config.rateLimitGate(0, "postgres", null).enabled());
        assertTrue(config.rateLimitGate(1, "memory", null).enabled());
    }

    @Test void filterSkipsWebhooksEvenWhenEnabled() throws Exception {
        var gate = RateLimitGate.perMinute(1);
        var filter = new RateLimitFilter(gate);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhooks/github/acme/repo");
        assertTrue(filter.shouldNotFilter(request));
    }
}
