package com.wynk.codecontext.ops;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlatformMetricsTest {
    @Test void recordsIndexSearchAndMcpSignals() {
        var registry = new SimpleMeterRegistry();
        var metrics = new PlatformMetrics(registry);
        metrics.indexClaimed();
        metrics.indexSucceeded();
        metrics.indexFailed();
        metrics.leaseExpired(2);
        metrics.searchRequest();
        var sample = metrics.startMcp();
        metrics.recordMcp(sample, true);
        metrics.cleanupFailed();

        assertEquals(1.0, metrics.indexSucceededCount());
        assertEquals(1.0, metrics.indexFailedCount());
        assertEquals(1.0, metrics.searchRequestCount());
        assertEquals(1.0, metrics.mcpInvocationCount());
        assertEquals(1.0, metrics.mcpErrorCount());
        assertNotNull(registry.find("ccp_index_jobs_claimed_total").counter());
        assertNotNull(registry.find("ccp_lease_expired_total").counter());
        assertEquals(2.0, registry.find("ccp_lease_expired_total").counter().count());
        assertNotNull(registry.find("ccp_cleanup_outbox_failed").counter());
        assertNotNull(registry.find("ccp_mcp_tool_latency_seconds").timer());
    }
}
