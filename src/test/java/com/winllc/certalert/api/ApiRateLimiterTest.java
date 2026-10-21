package com.winllc.certalert.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** A token's allowance: a minute's worth at once, refilled evenly, per token. */
class ApiRateLimiterTest {

    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-21T12:00:00Z");

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }
    }

    @Test
    void aTokenOverItsRateWaitsAndOthersDoNot() {
        ApiProperties properties = new ApiProperties();
        properties.setRequestsPerMinute(60);
        MovableClock clock = new MovableClock();
        ApiRateLimiter limiter = new ApiRateLimiter(properties, clock);

        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire(1L)).isZero();
        }
        assertThat(limiter.acquire(1L)).as("seconds to wait").isEqualTo(1);
        // Another token has its own allowance.
        assertThat(limiter.acquire(2L)).isZero();

        clock.advance(Duration.ofSeconds(1));
        assertThat(limiter.acquire(1L)).isZero();
        assertThat(limiter.acquire(1L)).isPositive();

        clock.advance(Duration.ofMinutes(5));
        for (int i = 0; i < 60; i++) {
            assertThat(limiter.acquire(1L)).isZero();
        }
    }

    @Test
    void zeroTurnsItOff() {
        ApiProperties properties = new ApiProperties();
        properties.setRequestsPerMinute(0);
        ApiRateLimiter limiter = new ApiRateLimiter(properties, new MovableClock());
        for (int i = 0; i < 1000; i++) {
            assertThat(limiter.acquire(1L)).isZero();
        }
    }
}
