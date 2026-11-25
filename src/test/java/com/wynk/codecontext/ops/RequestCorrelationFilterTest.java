package com.wynk.codecontext.ops;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RequestCorrelationFilterTest {
    @Test void generatesItsOwnIdentityAndRestoresTheCallingThreadsContext() throws Exception {
        var filter = new RequestCorrelationFilter();
        var request = new MockHttpServletRequest("GET", "/mcp");
        request.addHeader("X-Request-ID", "untrusted-secret-or-log-injection");
        request.setQueryString("token=never-log-this");
        var response = new MockHttpServletResponse();
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start();
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(RequestCorrelationFilter.class);
        logger.addAppender(events);
        MDC.put("request_id", "outer");
        try {
            filter.doFilter(request, response, (req, res) -> {
                String id = response.getHeader("X-Request-ID");
                assertDoesNotThrow(() -> UUID.fromString(id));
                assertEquals(id, MDC.get("request_id"));
                assertNotEquals("untrusted-secret-or-log-injection", id);
            });
            assertEquals("outer", MDC.get("request_id"));
            assertEquals(1, events.list.size());
            String message = events.list.getFirst().getFormattedMessage();
            assertFalse(message.contains("untrusted-secret-or-log-injection"));
            assertFalse(message.contains("never-log-this"));
            assertFalse(message.contains("/mcp"));
        } finally { MDC.remove("request_id"); logger.detachAppender(events); events.stop(); }
    }

    @Test void preservesIdentityOnAsyncAndErrorDispatchAndCleansUpAfterExceptions() throws Exception {
        var filter = new RequestCorrelationFilter();
        var request = new MockHttpServletRequest("POST", "/mcp");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {});
        String id = response.getHeader("X-Request-ID");
        for (var dispatch : new DispatcherType[]{DispatcherType.ASYNC, DispatcherType.ERROR}) {
            request.setDispatcherType(dispatch);
            assertThrows(ServletException.class, () -> filter.doFilter(request, response, (req, res) -> {
                assertEquals(id, MDC.get("request_id"));
                throw new ServletException("test failure");
            }));
            assertEquals(id, response.getHeader("X-Request-ID"));
            assertNull(MDC.get("request_id"));
        }
        var next = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest(), next, (req, res) -> {});
        assertNotEquals(id, next.getHeader("X-Request-ID"));
        assertNull(MDC.get("request_id"));
    }

    @Test void nestedErrorDispatchRestoresOuterRequestScope() throws Exception {
        var filter = new RequestCorrelationFilter();
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            String id = MDC.get("request_id");
            request.setDispatcherType(DispatcherType.ERROR);
            filter.doFilter(request, response, (innerReq, innerRes) -> assertEquals(id, MDC.get("request_id")));
            assertEquals(id, MDC.get("request_id"));
        });
        assertNull(MDC.get("request_id"));
    }
}
