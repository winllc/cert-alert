package com.winllc.certalert.service;

import com.winllc.certalert.config.CertAlertProperties;
import com.winllc.certalert.domain.CheckStatus;
import com.winllc.certalert.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Turns a certificate's expiry date into a status and a severity, using the configured thresholds. */
@Component
public class CertificateStatusEvaluator {

    private final CertAlertProperties properties;

    public CertificateStatusEvaluator(CertAlertProperties properties) {
        this.properties = properties;
    }

    /** Whole days between {@code now} and {@code notAfter}; negative once the certificate has expired. */
    public long daysUntilExpiry(Instant notAfter, Instant now) {
        return Duration.between(now, notAfter).toDays();
    }

    public CheckStatus evaluate(Instant notAfter, Instant now) {
        if (!notAfter.isAfter(now)) {
            return CheckStatus.EXPIRED;
        }
        return daysUntilExpiry(notAfter, now) < properties.getWarningThresholdDays()
                ? CheckStatus.EXPIRING_SOON
                : CheckStatus.VALID;
    }

    public Severity severityFor(CheckStatus status, Long daysUntilExpiry) {
        return switch (status) {
            case VALID -> Severity.INFO;
            case EXPIRED, UNREACHABLE -> Severity.CRITICAL;
            case EXPIRING_SOON -> daysUntilExpiry != null && daysUntilExpiry < properties.getCriticalThresholdDays()
                    ? Severity.CRITICAL
                    : Severity.WARNING;
        };
    }
}
