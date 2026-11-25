package com.wynk.codecontext.security;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Fixed-minute team budget; production can coordinate replicas through PostgreSQL. */
public final class RateLimitGate {
    public record Decision(boolean allowed, long retryAfterMillis, int remaining) {
        public static Decision allow(int remaining) {
            return new Decision(true, 0L, remaining);
        }
        public static Decision deny(long retryAfterMillis) {
            return new Decision(false, Math.max(0L, retryAfterMillis), 0);
        }
    }

    public static final class UnavailableException extends RuntimeException {
        private UnavailableException(Throwable cause) { super("rate-limit storage unavailable", cause); }
    }
    private final java.util.function.Function<String, Decision> shared;
    private final int limitPerMinute;
    private final Clock clock;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private RateLimitGate(int limitPerMinute, Clock clock) {
        this(limitPerMinute, clock, null);
    }

    private RateLimitGate(int limitPerMinute, Clock clock, java.util.function.Function<String, Decision> shared) {
        this.shared = shared;
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

    /** One atomic UPSERT, using the database clock and one bounded row per team, across all replicas. */
    public static RateLimitGate postgres(int limit, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        if (limit <= 0) return disabled();
        return new RateLimitGate(limit, Clock.systemUTC(), key -> {
            try {
                return jdbc.queryForObject("""
                    INSERT INTO api_rate_limit_windows AS budget(team, window_start, used)
                    VALUES (?, date_trunc('minute', statement_timestamp()), 1)
                    ON CONFLICT (team) DO UPDATE SET
                      used = CASE WHEN budget.window_start < date_trunc('minute', statement_timestamp()) THEN 1
                                  ELSE LEAST(budget.used + 1, 2147483648::bigint) END,
                      window_start = GREATEST(budget.window_start, date_trunc('minute', statement_timestamp()))
                    RETURNING used, GREATEST(1, ceil(extract(epoch FROM
                      (window_start + interval '1 minute' - statement_timestamp())) * 1000))::bigint AS retry_ms
                    """, (rs, row) -> rs.getLong("used") > limit ? Decision.deny(rs.getLong("retry_ms"))
                        : Decision.allow(limit - rs.getInt("used")), key);
            } catch (org.springframework.dao.DataAccessException failure) {
                throw new UnavailableException(failure);
            }
        });
    }

    public boolean enabled() {
        return limitPerMinute > 0;
    }

    public Decision check(String bucketKey) {
        if (!enabled()) {
            return Decision.allow(Integer.MAX_VALUE);
        }
        String key = bucketKey == null || bucketKey.isBlank() ? "anonymous" : bucketKey;
        if (shared != null) return shared.apply(key);
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
