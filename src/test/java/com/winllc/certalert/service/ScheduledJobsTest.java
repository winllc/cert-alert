package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.winllc.certalert.alert.AlertDispatcher;
import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Severity;
import com.winllc.certalert.repository.CachedCertificateRepository;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The two jobs that do not read the directory: re-evaluating cached expiry, and removing
 * entries the directory has stopped publishing.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "cert-alert.ldap.prune.after=7d")
class ScheduledJobsTest {

    @Autowired
    private CertificateRefreshService refreshService;

    @Autowired
    private DirectoryPruneService pruneService;

    @Autowired
    private DirectoryUserRepository userRepository;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private CachedCertificateRepository certificateRepository;

    @MockitoBean
    private AlertDispatcher alertDispatcher;

    @BeforeEach
    void reset() {
        serverRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void refreshMovesACertificateThatExpiredSinceTheLastSync() {
        Instant now = Instant.now();
        // Cached while it was still valid; its notAfter has since passed. Nothing in the
        // directory changed, so only this job can notice.
        DirectoryUser user = userRepository.save(userWith(
                "uid=lapsed,ou=people", "Lapsed Holder", "lapsed@example.gov",
                certificate("CN=lapsed", now.minus(Duration.ofHours(6)), CertificateStatus.EXPIRING_SOON, now)));

        DirectorySyncResult result = refreshService.refresh();

        assertThat(result.entriesSeen()).isPositive();
        assertThat(result.alertsRaised()).isEqualTo(1);

        CachedCertificate refreshed = certificateRepository.findAll().getFirst();
        assertThat(refreshed.getStatus()).isEqualTo(CertificateStatus.EXPIRED);

        // The roll-up the search tables filter on moves with it.
        DirectoryUser reloaded = userRepository.findById(user.getId()).orElseThrow();
        assertThat(reloaded.getCertificateStatus()).isEqualTo(CertificateStatus.EXPIRED);

        ArgumentCaptor<CertificateAlert> alert = ArgumentCaptor.forClass(CertificateAlert.class);
        verify(alertDispatcher).dispatch(alert.capture());
        assertThat(alert.getValue().status()).isEqualTo(CertificateStatus.EXPIRED);
        assertThat(alert.getValue().severity()).isEqualTo(Severity.CRITICAL);
        assertThat(alert.getValue().contact()).isEqualTo("lapsed@example.gov");
    }

    @Test
    void refreshMovesACertificateIntoTheWarningWindow() {
        Instant now = Instant.now();
        userRepository.save(userWith(
                "uid=nearing,ou=people", "Nearing Holder", "nearing@example.gov",
                certificate("CN=nearing", now.plus(Duration.ofDays(10)), CertificateStatus.VALID, now)));

        refreshService.refresh();

        assertThat(certificateRepository.findAll().getFirst().getStatus())
                .isEqualTo(CertificateStatus.EXPIRING_SOON);
        verify(alertDispatcher).dispatch(any());
    }

    @Test
    void refreshLeavesCertificatesThatHaveNotMoved() {
        Instant now = Instant.now();
        userRepository.save(userWith(
                "uid=steady,ou=people", "Steady Holder", "steady@example.gov",
                certificate("CN=steady", now.plus(Duration.ofDays(500)), CertificateStatus.VALID, now)));

        DirectorySyncResult result = refreshService.refresh();

        // Well outside the warning window, so the job does not even look at it.
        assertThat(result.entriesSeen()).isZero();
        assertThat(certificateRepository.findAll().getFirst().getStatus()).isEqualTo(CertificateStatus.VALID);
        verify(alertDispatcher, never()).dispatch(any());
    }

    @Test
    void pruneRemovesOnlyEntriesUnseenForLongerThanTheWindow() {
        Instant now = Instant.now();
        DirectoryUser stale = userWith("uid=gone,ou=people", "Gone Holder", "gone@example.gov");
        stale.markSynced(now.minus(Duration.ofDays(30)));
        userRepository.save(stale);

        DirectoryUser fresh = userWith("uid=here,ou=people", "Here Holder", "here@example.gov");
        fresh.markSynced(now);
        userRepository.save(fresh);

        DirectoryServer staleServer = new DirectoryServer("cn=goneserver,ou=servers");
        staleServer.setCommonName("goneserver");
        staleServer.setServerPocs(List.of("gone@example.gov"));
        staleServer.markSynced(now.minus(Duration.ofDays(30)));
        staleServer.refreshCertificateSummary();
        serverRepository.save(staleServer);

        assertThat(pruneService.countPrunable()).isEqualTo(2);

        DirectorySyncResult result = pruneService.prune();

        assertThat(result.entriesPruned()).isEqualTo(2);
        assertThat(userRepository.findByDn("uid=gone,ou=people")).isEmpty();
        assertThat(userRepository.findByDn("uid=here,ou=people")).isPresent();
        assertThat(serverRepository.count()).isZero();
    }

    @Test
    void pruneTakesTheCachedCertificatesWithIt() {
        Instant now = Instant.now();
        DirectoryUser stale = userWith("uid=goneWithCerts,ou=people", "Gone Holder", "gone2@example.gov",
                certificate("CN=gone2", now.plus(Duration.ofDays(100)), CertificateStatus.VALID, now));
        stale.markSynced(now.minus(Duration.ofDays(30)));
        userRepository.save(stale);
        assertThat(certificateRepository.count()).isEqualTo(1);

        pruneService.prune();

        assertThat(certificateRepository.count()).isZero();
    }

    private DirectoryUser userWith(String dn, String displayName, String email, CachedCertificate... certificates) {
        DirectoryUser user = new DirectoryUser(dn);
        user.setDisplayName(displayName);
        user.setCommonName(displayName);
        user.setIcEmail(email);
        for (CachedCertificate certificate : certificates) {
            user.addCertificate(certificate);
        }
        user.refreshIdentifiers(email);
        user.markSynced(Instant.now());
        user.refreshCertificateSummary();
        return user;
    }

    /** A certificate carrying a status that may no longer match its notAfter. */
    private CachedCertificate certificate(
            String subject, Instant notAfter, CertificateStatus cachedStatus, Instant cachedAt) {
        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) subject.hashCode())),
                "01",
                subject,
                "CN=Example CA",
                cachedAt.minus(Duration.ofDays(365)),
                notAfter,
                "SHA256withRSA",
                "SHA-256",
                "RSA",
                2048,
                null,
                cachedAt);
        certificate.updateStatus(cachedStatus, cachedAt);
        return certificate;
    }
}
