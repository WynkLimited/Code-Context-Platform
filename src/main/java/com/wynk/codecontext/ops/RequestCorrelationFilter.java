package com.wynk.codecontext.ops;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Server-owned identity across servlet dispatches; no headers, source, URI or query are logged. */
public final class RequestCorrelationFilter extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(RequestCorrelationFilter.class);
    private static final String ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".id";
    private static final String MDC_KEY = "request_id";

    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }
    @Override protected boolean shouldNotFilterErrorDispatch() { return false; }
    @Override protected void doFilterNestedErrorDispatch(HttpServletRequest request, HttpServletResponse response,
                                                         FilterChain chain) throws ServletException, IOException {
        doFilterInternal(request, response, chain);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                              FilterChain chain) throws ServletException, IOException {
        Object stored = request.getAttribute(ATTRIBUTE);
        UUID id = stored instanceof UUID existing ? existing : UUID.randomUUID();
        request.setAttribute(ATTRIBUTE, id);
        String previous = MDC.get(MDC_KEY);
        MDC.put(MDC_KEY, id.toString());
        if (!response.isCommitted()) response.setHeader("X-Request-ID", id.toString());
        long started = System.nanoTime();
        try { chain.doFilter(request, response); }
        finally {
            try {
                // A dispatch finishing does not imply a long-running async HTTP request completed.
                LOG.info("http.dispatch.finished dispatch={} status={} asyncStarted={} elapsedMs={}",
                        request.getDispatcherType(), response.getStatus(), request.isAsyncStarted(),
                        (System.nanoTime() - started) / 1_000_000);
            } finally {
                if (previous == null) MDC.remove(MDC_KEY); else MDC.put(MDC_KEY, previous);
            }
        }
    }
}
