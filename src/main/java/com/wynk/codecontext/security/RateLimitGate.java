package com.wynk.codecontext.security;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * In-process rate-limit hook for authenticated API/MCP callers.
 *
 * <p>Default deployment uses {@link #disabled()} so existing behaviour is unchanged. Operators can
 * enable a fixed per-minute budget via {@code code-context.rate-limit-per-minute}. Persistent /
 * multi-replica limiting is intentionally out of scope for this stub.
 */
public final class RateLimitGate {
    public record Decision(boolean allowed, long retryAfterMillis, int remaining) {
        public static Decision allow(int remaining) {
            return new Decision(true, 0L, remaining);
        }
        public static Decision deny(long retryAfterMillis) {
            return new Decision(false, Math.max(0L, retryAfterMillis), 0);
        }
    }

    private final int limitPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private RateLimitGate(int limitPerMinute, Clock clock) {
        this.limitPerMinute = limitPerMinute;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public static RateLimitGate disabled() {
        return new RateLimitGate(0, Clock.systemUTC());
    }

    public static RateLimitGate perMinute(int limit) {
        return perMinute(limit, Clock.systemUTC());
    }

    public static RateLimitGate perMinute(int limit, Clock clock) {
        if (limit <= 0) {
            return new RateLimitGate(0, clock);
        }
        return new RateLimitGate(limit, clock);
    }

    public boolean enabled() {
        return limitPerMinute > 0;
    }

    public Decision check(String bucketKey) {
        if (!enabled()) {
            return Decision.allow(Integer.MAX_VALUE);
        }
        String key = bucketKey == null || bucketKey.isBlank() ? "anonymous" : bucketKey;
        long now = clock.millis();
        long minute = now / 60_000L;
        Window window = windows.compute(key, (ignored, previous) -> {
            if (previous == null || previous.minute != minute) {
                return new Window(minute, new AtomicInteger(0));
            }
            return previous;
        });
        int used = window.count.incrementAndGet();
        if (used > limitPerMinute) {
            long retryAfter = ((minute + 1) * 60_000L) - now;
            return Decision.deny(retryAfter);
        }
        return Decision.allow(limitPerMinute - used);
    }

    private record Window(long minute, AtomicInteger count) {}
}
