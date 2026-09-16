package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.domain.CertificateCheck;
import com.winllc.certalert.domain.CertificateTarget;
import com.winllc.certalert.domain.CheckStatus;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.repository.CertificateCheckRepository;
import com.winllc.certalert.repository.CertificateTargetRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Exercises the monitor against a real database, with only the outbound TLS connection
 * and the alert fan-out stubbed.
 */
@SpringBootTest
@ActiveProfiles("test")
class CertificateMonitorServiceTest {

    @Autowired
    private CertificateMonitorService monitorService;

    @Autowired
    private CertificateTargetRepository targetRepository;

    @Autowired
    private CertificateCheckRepository checkRepository;

    @MockitoBean
    private CertificateInspector inspector;

    @MockitoBean
    private AlertDispatcher alertDispatcher;

    @BeforeEach
    void resetDatabase() {
        checkRepository.deleteAll();
        targetRepository.deleteAll();
    }

    @Test
    void healthyCertificateIsRecordedWithoutAlerting() {
        CertificateTarget target = givenTarget("healthy", "healthy.example.com");
        givenCertificateExpiringIn(Duration.ofDays(200));

        CertificateCheck check = monitorService.checkTarget(target.getId());

        assertThat(check.getStatus()).isEqualTo(CheckStatus.VALID);
        assertThat(check.getIssuer()).isEqualTo("CN=Example CA");
        assertThat(check.getDaysUntilExpiry()).isEqualTo(200);
        verify(alertDispatcher, never()).dispatch(any());

        CertificateTarget reloaded = targetRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getLastStatus()).isEqualTo(CheckStatus.VALID);
        assertThat(reloaded.getLastCheckedAt()).isNotNull();
        assertThat(reloaded.getLastExpiresAt()).isNotNull();
    }

    @Test
    void certificateNearingExpiryAlertsWithWarningSeverity() {
        CertificateTarget target = givenTarget("expiring", "expiring.example.com");
        givenCertificateExpiringIn(Duration.ofDays(20));

        CertificateCheck check = monitorService.checkTarget(target.getId());

        assertThat(check.getStatus()).isEqualTo(CheckStatus.EXPIRING_SOON);
        ArgumentCaptor<CertificateAlert> alert = ArgumentCaptor.forClass(CertificateAlert.class);
        verify(alertDispatcher).dispatch(alert.capture());
        assertThat(alert.getValue().severity()).isEqualTo(Severity.WARNING);
        assertThat(alert.getValue().targetName()).isEqualTo("expiring");
    }

    @Test
    void certificateInsideCriticalWindowAlertsWithCriticalSeverity() {
        CertificateTarget target = givenTarget("urgent", "urgent.example.com");
        givenCertificateExpiringIn(Duration.ofDays(2));

        monitorService.checkTarget(target.getId());

        ArgumentCaptor<CertificateAlert> alert = ArgumentCaptor.forClass(CertificateAlert.class);
        verify(alertDispatcher).dispatch(alert.capture());
        assertThat(alert.getValue().severity()).isEqualTo(Severity.CRITICAL);
    }

    @Test
    void unreachableEndpointIsRecordedAsFailureAndAlerts() {
        CertificateTarget target = givenTarget("down", "down.example.com");
        when(inspector.inspect(anyString(), anyInt()))
                .thenThrow(new CertificateInspectionException("connection refused"));

        CertificateCheck check = monitorService.checkTarget(target.getId());

        assertThat(check.getStatus()).isEqualTo(CheckStatus.UNREACHABLE);
        assertThat(check.getErrorMessage()).isEqualTo("connection refused");
        assertThat(check.getNotAfter()).isNull();
        verify(alertDispatcher).dispatch(any());
    }

    @Test
    void repeatAlertsForAnUnchangedStateAreSuppressed() {
        CertificateTarget target = givenTarget("noisy", "noisy.example.com");
        givenCertificateExpiringIn(Duration.ofDays(20));

        monitorService.checkTarget(target.getId());
        monitorService.checkTarget(target.getId());
        monitorService.checkTarget(target.getId());

        verify(alertDispatcher, times(1)).dispatch(any());
        assertThat(checkRepository.count()).isEqualTo(3);
    }

    @Test
    void recoveryClearsAlertStateAndNotifies() {
        CertificateTarget target = givenTarget("recovering", "recovering.example.com");
        givenCertificateExpiringIn(Duration.ofDays(20));
        monitorService.checkTarget(target.getId());

        givenCertificateExpiringIn(Duration.ofDays(120));
        monitorService.checkTarget(target.getId());

        ArgumentCaptor<CertificateAlert> alerts = ArgumentCaptor.forClass(CertificateAlert.class);
        verify(alertDispatcher, times(2)).dispatch(alerts.capture());
        CertificateAlert recovery = alerts.getAllValues().get(1);
        assertThat(recovery.status()).isEqualTo(CheckStatus.VALID);
        assertThat(recovery.severity()).isEqualTo(Severity.INFO);

        CertificateTarget reloaded = targetRepository.findById(target.getId()).orElseThrow();
        assertThat(reloaded.getLastAlertedStatus()).isNull();
        assertThat(reloaded.getLastAlertedAt()).isNull();
    }

    @Test
    void missingTargetIsReportedAsNotFound() {
        assertThat(targetRepository.findById(9999L)).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> monitorService.checkTarget(9999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private CertificateTarget givenTarget(String name, String hostname) {
        return targetRepository.save(new CertificateTarget(name, hostname, 443));
    }

    /**
     * Stubs the inspector with a certificate expiring after {@code remaining}. A minute of
     * slack is added so the whole-day count does not round down while the test runs.
     */
    private void givenCertificateExpiringIn(Duration remaining) {
        Instant now = Instant.now();
        when(inspector.inspect(anyString(), anyInt()))
                .thenReturn(new CertificateDetails(
                        "CN=example.com",
                        "CN=Example CA",
                        "0a1b2c3d",
                        now.minus(Duration.ofDays(30)),
                        now.plus(remaining).plusSeconds(60),
                        List.of("example.com", "www.example.com"),
                        "SHA256withRSA"));
    }
}
