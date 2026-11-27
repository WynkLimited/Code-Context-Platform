package com.wynk.codecontext.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Optional servlet filter that applies {@link RateLimitGate} after authentication.
 *
 * <p>Webhooks and actuator health remain unlimited. When the gate is disabled the filter is a
 * no-op pass-through.
 */
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitGate gate;

    public RateLimitFilter(RateLimitGate gate) {
        this.gate = gate;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!gate.enabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return path != null && (path.startsWith("/webhooks/") || path.startsWith("/actuator/health"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String bucket = authentication == null || authentication.getPrincipal() == null
                ? "anonymous"
                : String.valueOf(authentication.getPrincipal());
        RateLimitGate.Decision decision;
        try { decision = gate.check(bucket); }
        catch (RateLimitGate.UnavailableException failure) {
            logger.warn("Rate-limit storage unavailable; authenticated request rejected");
            response.setStatus(503);
            response.setHeader("Retry-After", "1");
            response.setContentType("text/plain");
            response.getWriter().write("request budget temporarily unavailable");
            return;
        }
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(Math.max(1L, (decision.retryAfterMillis() + 999) / 1000)));
            response.setContentType("text/plain");
            response.getWriter().write("rate limit exceeded");
            return;
        }
        if (decision.remaining() != Integer.MAX_VALUE) {
            response.setHeader("X-RateLimit-Remaining", Integer.toString(decision.remaining()));
        }
        chain.doFilter(request, response);
    }
}
