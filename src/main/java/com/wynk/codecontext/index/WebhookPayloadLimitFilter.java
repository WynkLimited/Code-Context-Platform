package com.wynk.codecontext.index;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Reject oversized or unbounded GitHub payloads before Spring buffers their request bodies. */
@Component
public final class WebhookPayloadLimitFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!request.getRequestURI().startsWith(request.getContextPath() + "/webhooks/github/")) {
            filterChain.doFilter(request, response);
            return;
        }
        long length = request.getContentLengthLong();
        if (length < 0) {
            response.sendError(HttpServletResponse.SC_LENGTH_REQUIRED, "webhook Content-Length is required");
            return;
        }
        if (length > IndexWebhookController.MAX_PAYLOAD_BYTES) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "webhook payload exceeds 1 MiB");
            return;
        }
        filterChain.doFilter(request, response);
    }
}
