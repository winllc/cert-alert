package com.winllc.certalert.api;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The public API, bound from {@code cert-alert.api}.
 *
 * <p>The API itself is always there for a signed-in browser - the pages are built on it. What
 * this controls is the other way in: a bearer token an administrator created.
 */
@ConfigurationProperties(prefix = "cert-alert.api")
public class ApiProperties {

    /** Whether API tokens authenticate at all. Off refuses every token (401) without deleting any. */
    private boolean tokensEnabled = true;

    /**
     * Requests one token may make in a minute, on each replica. Beyond it the answer is 429
     * with {@code Retry-After}. 0 turns the limit off.
     */
    private int requestsPerMinute = 300;

    /**
     * The longest a token may be created for. Unset allows a token without an expiry; set,
     * every token must have one, no later than this from its creation.
     */
    private Duration maxLifetime;

    /**
     * How often one token's use is written to the audit trail. Every request a token makes
     * that changes something is audited as it happens; reads are summarised to one
     * {@code api.token-used} event per token per this interval.
     */
    private Duration useAuditInterval = Duration.ofHours(1);

    /**
     * How long a creator's roles, worked out again from the directory as a token is used,
     * are remembered. A change of role takes up to this long to reach their tokens.
     */
    private Duration creatorRolesCacheFor = Duration.ofMinutes(1);

    /** The largest page a list endpoint returns. */
    private int maxPageSize = 500;

    public boolean isTokensEnabled() {
        return tokensEnabled;
    }

    public void setTokensEnabled(boolean tokensEnabled) {
        this.tokensEnabled = tokensEnabled;
    }

    public int getRequestsPerMinute() {
        return requestsPerMinute;
    }

    public void setRequestsPerMinute(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    public Duration getMaxLifetime() {
        return maxLifetime;
    }

    public void setMaxLifetime(Duration maxLifetime) {
        this.maxLifetime = maxLifetime;
    }

    public Duration getUseAuditInterval() {
        return useAuditInterval;
    }

    public void setUseAuditInterval(Duration useAuditInterval) {
        this.useAuditInterval = useAuditInterval;
    }

    public Duration getCreatorRolesCacheFor() {
        return creatorRolesCacheFor;
    }

    public void setCreatorRolesCacheFor(Duration creatorRolesCacheFor) {
        this.creatorRolesCacheFor = creatorRolesCacheFor;
    }

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }
}
