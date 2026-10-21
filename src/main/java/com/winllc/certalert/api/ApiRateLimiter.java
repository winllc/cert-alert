package com.winllc.certalert.api;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Requests per token per minute, as a token bucket: a client may spend a minute's allowance
 * at once, and it refills evenly. On each replica, so N replicas allow up to N times the
 * figure; the point is to keep one runaway client from starving everybody else, not to bill.
 */
@Component
public class ApiRateLimiter {

    private final ApiProperties properties;
    private final Clock clock;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public ApiRateLimiter(ApiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Takes one request from this token's allowance.
     *
     * @return 0 where it may go ahead; otherwise how many seconds until it may
     */
    public long acquire(Long tokenId) {
        int perMinute = properties.getRequestsPerMinute();
        if (perMinute <= 0) {
            return 0;
        }
        long now = clock.millis();
        Bucket bucket = buckets.computeIfAbsent(tokenId, id -> new Bucket(perMinute, now));
        synchronized (bucket) {
            double perMilli = perMinute / 60_000.0;
            bucket.tokens = Math.min(perMinute, bucket.tokens + (now - bucket.updated) * perMilli);
            bucket.updated = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return 0;
            }
            return Math.max(1, (long) Math.ceil((1 - bucket.tokens) / perMilli / 1000.0));
        }
    }

    private static final class Bucket {
        private double tokens;
        private long updated;

        private Bucket(double tokens, long updated) {
            this.tokens = tokens;
            this.updated = updated;
        }
    }
}
