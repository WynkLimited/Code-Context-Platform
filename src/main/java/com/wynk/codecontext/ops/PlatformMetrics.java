package com.wynk.codecontext.ops;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Domain Micrometer meters for indexing, search, MCP and cleanup signals. */
@Component
public class PlatformMetrics {
    private final MeterRegistry registry;
    private final Counter indexClaimed;
    private final Counter indexSucceeded;
    private final Counter indexFailed;
    private final Counter leaseExpired;
    private final Counter searchRequests;
    private final Counter mcpInvocations;
    private final Counter mcpErrors;
    private final Counter cleanupFailed;
    private final Timer mcpLatency;

    public PlatformMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.indexClaimed = Counter.builder("ccp_index_jobs_claimed_total").register(registry);
        this.indexSucceeded = Counter.builder("ccp_index_jobs_succeeded_total").register(registry);
        this.indexFailed = Counter.builder("ccp_index_jobs_failed_total").register(registry);
        this.leaseExpired = Counter.builder("ccp_lease_expired_total").register(registry);
        this.searchRequests = Counter.builder("ccp_search_requests_total").register(registry);
        this.mcpInvocations = Counter.builder("ccp_mcp_tool_invocations_total").register(registry);
        this.mcpErrors = Counter.builder("ccp_mcp_tool_errors_total").register(registry);
        this.cleanupFailed = Counter.builder("ccp_cleanup_outbox_failed").register(registry);
        this.mcpLatency = Timer.builder("ccp_mcp_tool_latency_seconds").register(registry);
    }

    public void indexClaimed() { indexClaimed.increment(); }
    public void indexSucceeded() { indexSucceeded.increment(); }
    public void indexFailed() { indexFailed.increment(); }
    public void leaseExpired(int count) { if (count > 0) leaseExpired.increment(count); }
    public void searchRequest() { searchRequests.increment(); }
    public void cleanupFailed() { cleanupFailed.increment(); }

    public Timer.Sample startMcp() { return Timer.start(registry); }

    public void recordMcp(Timer.Sample sample, boolean error) {
        if (sample != null) sample.stop(mcpLatency);
        mcpInvocations.increment();
        if (error) mcpErrors.increment();
    }

    public double indexSucceededCount() { return indexSucceeded.count(); }
    public double indexFailedCount() { return indexFailed.count(); }
    public double searchRequestCount() { return searchRequests.count(); }
    public double mcpInvocationCount() { return mcpInvocations.count(); }
    public double mcpErrorCount() { return mcpErrors.count(); }
}
