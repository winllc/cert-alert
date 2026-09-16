package com.winllc.certalert.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Tunables for certificate monitoring, bound from the {@code cert-alert} prefix.
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

    /** Socket connect timeout used when retrieving a certificate chain. */
    @NotNull
    private Duration connectTimeout = Duration.ofSeconds(10);

    /** Socket read timeout used while completing the TLS handshake. */
    @NotNull
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * Minimum time between repeat alerts for a target that stays in the same unhealthy
     * state. A change of state always alerts immediately.
     */
    @NotNull
    private Duration alertRepeatInterval = Duration.ofHours(24);

    /** Cron expression driving the scheduled sweep of all enabled targets. */
    @NotNull
    private String scanCron = "0 0 * * * *";

    /** Whether the scheduled sweep is enabled. Disable it to drive checks via the API only. */
    private boolean scanEnabled = true;

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

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public Duration getAlertRepeatInterval() {
        return alertRepeatInterval;
    }

    public void setAlertRepeatInterval(Duration alertRepeatInterval) {
        this.alertRepeatInterval = alertRepeatInterval;
    }

    public String getScanCron() {
        return scanCron;
    }

    public void setScanCron(String scanCron) {
        this.scanCron = scanCron;
    }

    public boolean isScanEnabled() {
        return scanEnabled;
    }

    public void setScanEnabled(boolean scanEnabled) {
        this.scanEnabled = scanEnabled;
    }
}
