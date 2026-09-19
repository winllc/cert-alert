package com.winllc.certalert.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Where "too many" starts, bound from {@code cert-alert.risk}.
 *
 * <p>Both of these are judgements rather than rules - a load balancer fronting forty
 * services has forty names for a reason - so they are configuration, and the defaults are
 * only what is usual.
 */
@Validated
@ConfigurationProperties(prefix = "cert-alert.risk")
public class RiskProperties {

    /** More subject alternative names than this and the certificate is flagged. */
    @Min(1)
    private int maxSubjectAltNames = 20;

    /**
     * More distinct registrable domains than this and it is flagged. A certificate for one
     * service names one domain; one naming six says somebody batched a renewal.
     */
    @Min(1)
    private int maxDomains = 3;

    public int getMaxSubjectAltNames() {
        return maxSubjectAltNames;
    }

    public void setMaxSubjectAltNames(int maxSubjectAltNames) {
        this.maxSubjectAltNames = maxSubjectAltNames;
    }

    public int getMaxDomains() {
        return maxDomains;
    }

    public void setMaxDomains(int maxDomains) {
        this.maxDomains = maxDomains;
    }
}
