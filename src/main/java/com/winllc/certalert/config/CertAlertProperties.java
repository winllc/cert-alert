package com.winllc.certalert.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Expiry thresholds, bound from the {@code cert-alert} prefix. Directory scraping is
 * configured separately under {@code cert-alert.ldap}.
 */
@Validated
@ConfigurationProperties(prefix = "cert-alert")
public class CertAlertProperties {

    /** Certificates expiring within this many days are reported as EXPIRING_SOON. */
    @Min(1)
    private int warningThresholdDays = 30;

    /** Certificates expiring within this many days are escalated to CRITICAL severity. */
    @Min(1)
    private int criticalThresholdDays = 7;

    public int getWarningThresholdDays() {
        return warningThresholdDays;
    }

    public void setWarningThresholdDays(int warningThresholdDays) {
        this.warningThresholdDays = warningThresholdDays;
    }

    public int getCriticalThresholdDays() {
        return criticalThresholdDays;
    }

    public void setCriticalThresholdDays(int criticalThresholdDays) {
        this.criticalThresholdDays = criticalThresholdDays;
    }
}
