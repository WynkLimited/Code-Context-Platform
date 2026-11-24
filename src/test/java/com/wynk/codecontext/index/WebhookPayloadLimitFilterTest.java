package com.wynk.codecontext.index;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookPayloadLimitFilterTest {
    private final WebhookPayloadLimitFilter filter = new WebhookPayloadLimitFilter();

    @Test
    void rejectsOversizedPayloadBeforeControllerBodyBinding() throws Exception {
        var request = new MockHttpServletRequest("POST", "/webhooks/github/owner/repository");
        request.setContent(new byte[IndexWebhookController.MAX_PAYLOAD_BYTES + 1]);
        var response = new MockHttpServletResponse();
        var proceeded = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> proceeded.set(true));

        assertEquals(413, response.getStatus());
        assertTrue(!proceeded.get(), "oversized bodies must stop before MVC buffers them");
    }

    @Test
    void requiresBoundedContentLengthForWebhookRequests() throws Exception {
        var request = new MockHttpServletRequest("POST", "/webhooks/github/owner/repository");
        var response = new MockHttpServletResponse();
        var proceeded = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> proceeded.set(true));

        assertEquals(411, response.getStatus());
        assertTrue(!proceeded.get());
    }
}
