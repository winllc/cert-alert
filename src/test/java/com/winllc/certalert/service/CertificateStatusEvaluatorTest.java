package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.config.CertAlertProperties;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CertificateStatusEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private CertificateStatusEvaluator evaluator;

    @BeforeEach
    void setUp() {
        CertAlertProperties properties = new CertAlertProperties();
        properties.setWarningThresholdDays(30);
        properties.setCriticalThresholdDays(7);
        evaluator = new CertificateStatusEvaluator(properties);
    }

    @Test
    void certificateWellInsideValidityIsValid() {
        assertThat(evaluator.evaluate(NOW.plus(Duration.ofDays(90)), NOW)).isEqualTo(CertificateStatus.VALID);
    }

    @Test
    void certificateExactlyAtWarningThresholdIsStillValid() {
        assertThat(evaluator.evaluate(NOW.plus(Duration.ofDays(30)), NOW)).isEqualTo(CertificateStatus.VALID);
    }

    @Test
    void certificateInsideWarningWindowIsExpiringSoon() {
        assertThat(evaluator.evaluate(NOW.plus(Duration.ofDays(29)), NOW)).isEqualTo(CertificateStatus.EXPIRING_SOON);
    }

    @Test
    void certificatePastNotAfterIsExpired() {
        assertThat(evaluator.evaluate(NOW.minus(Duration.ofSeconds(1)), NOW)).isEqualTo(CertificateStatus.EXPIRED);
    }

    @Test
    void certificateExpiringExactlyNowIsExpired() {
        assertThat(evaluator.evaluate(NOW, NOW)).isEqualTo(CertificateStatus.EXPIRED);
    }

    @Test
    void daysUntilExpiryIsNegativeOnceExpired() {
        assertThat(evaluator.daysUntilExpiry(NOW.minus(Duration.ofDays(3)), NOW)).isEqualTo(-3);
    }

    @Test
    void expiringSoonInsideCriticalWindowIsCritical() {
        assertThat(evaluator.severityFor(CertificateStatus.EXPIRING_SOON, 3L)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void expiringSoonOutsideCriticalWindowIsWarning() {
        assertThat(evaluator.severityFor(CertificateStatus.EXPIRING_SOON, 20L)).isEqualTo(Severity.WARNING);
    }

    @Test
    void expiredIsAlwaysCritical() {
        assertThat(evaluator.severityFor(CertificateStatus.EXPIRED, -1L)).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void anAbsentExpiryIsReportedAsNoCertificate() {
        assertThat(evaluator.evaluate(null, NOW)).isEqualTo(CertificateStatus.NONE);
    }
}
