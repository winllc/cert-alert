package com.winllc.certalert.alert;

import com.winllc.certalert.domain.CertificateCheck;
import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.domain.CheckStatus;
import com.winllc.certalert.domain.Severity;
import java.time.Instant;

/**
 * A single alert-worthy observation about a target, handed to every {@link AlertNotifier}.
 *
 * @param targetName human readable name of the target
 * @param hostname host that was checked
 * @param port port that was checked
 * @param status status that triggered the alert
 * @param severity how urgent the alert is
 * @param notAfter expiry of the certificate, null when the endpoint was unreachable
 * @param daysUntilExpiry days left before expiry, null when the endpoint was unreachable
 * @param detail failure message or other supporting detail, may be null
 * @param raisedAt when the alert was raised
 */
public record CertificateAlert(
        String targetName,
        String hostname,
        int port,
        CheckStatus status,
        Severity severity,
        Instant notAfter,
        Long daysUntilExpiry,
        String detail,
        Instant raisedAt) {

    public static CertificateAlert from(CertificateTarget target, CertificateCheck check, Severity severity) {
        return new CertificateAlert(
                target.getName(),
                target.getHostname(),
                target.getPort(),
                check.getStatus(),
                severity,
                check.getNotAfter(),
                check.getDaysUntilExpiry(),
                check.getErrorMessage(),
                check.getCheckedAt());
    }

    /** A one-line summary suitable for a log line, a chat message, or an email subject. */
    public String summary() {
        return switch (status) {
            case UNREACHABLE -> "[%s] %s (%s:%d) is unreachable: %s"
                    .formatted(severity, targetName, hostname, port, detail == null ? "unknown error" : detail);
            case EXPIRED -> "[%s] %s (%s:%d) certificate expired on %s"
                    .formatted(severity, targetName, hostname, port, notAfter);
            case EXPIRING_SOON -> "[%s] %s (%s:%d) certificate expires in %d day(s), on %s"
                    .formatted(severity, targetName, hostname, port, daysUntilExpiry, notAfter);
            case VALID -> "[%s] %s (%s:%d) certificate is valid until %s"
                    .formatted(severity, targetName, hostname, port, notAfter);
        };
    }
}
