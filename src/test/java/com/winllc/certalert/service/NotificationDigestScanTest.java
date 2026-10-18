package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

/**
 * How much of the cache one round-up reads, and that what has already expired cannot crowd
 * out what is about to.
 *
 * <p>Run with a limit of three so a handful of rows stands in for a directory publishing
 * tens of thousands of old certificates, read two at a time so every read crosses a page.
 * {@link NotificationDigestScaleTest} does it with a hundred thousand.
 */
@SpringBootTest(properties = {
    "cert-alert.notifications.digest.scan-limit=" + NotificationDigestScanTest.LIMIT,
    "cert-alert.notifications.digest.page-size=2",
    // Spring Boot's tests record no metrics unless asked; ens_certalert_digest_truncated_total is.
    "management.defaults.metrics.export.enabled=true"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class NotificationDigestScanTest {

    static final int LIMIT = 3;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private io.micrometer.core.instrument.MeterRegistry meters;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private DirectoryServerRepository servers;

    private Instant now;

    @BeforeEach
    void clear() {
        notifications.deleteAll();
        servers.deleteAll();
        users.deleteAll();
        now = Instant.now();
    }

    /**
     * More lapsed certificates than the limit - each the only one its entry holds, so each
     * is genuinely still somebody's problem - and one expiring next week. Read together,
     * soonest first, the expired filled the read and next week's was never reached.
     */
    @Test
    void certificatesExpiringNextWeekAreReportedHoweverManyHaveAlreadyExpired(CapturedOutput output) {
        for (int i = 0; i < LIMIT + 2; i++) {
            DirectoryUser lapsed = user("lapsed" + i, "lapsed" + i + "@example.gov");
            lapsed.addCertificate(certificate(
                    "CN=lapsed" + i, now.minus(Duration.ofDays(400 + i)), now.minus(Duration.ofDays(30 + i)),
                    CertificateStatus.EXPIRED));
            save(lapsed);
        }
        DirectoryUser soon = user("soon", "soon@example.gov");
        soon.addCertificate(certificate(
                "CN=soon", now.minus(Duration.ofDays(358)), now.plus(Duration.ofDays(7)),
                CertificateStatus.EXPIRING_SOON));
        save(soon);

        double truncatedBefore = truncated();
        notificationService.digest();
        // Stopping at the limit is counted, for an alert on it.
        org.assertj.core.api.Assertions.assertThat(truncated()).isEqualTo(truncatedBefore + 1);

        assertThat(addressedTo("soon@example.gov")).hasSize(1);
        // The most recently expired are the ones kept; the longest gone are left out.
        assertThat(addressedTo("lapsed0@example.gov")).hasSize(1);
        assertThat(addressedTo("lapsed" + (LIMIT + 1) + "@example.gov")).isEmpty();
        // And the warning says which read was cut short.
        assertThat(output.getOut())
                .contains("expired certificate(s) are still published by entries holding nothing current")
                .doesNotContain("certificate(s) expire in the next");
    }

    /** The same holds the other way round: the upcoming read has its own limit, and says so. */
    @Test
    void reachingTheLimitOnWhatIsAboutToExpireSaysSo(CapturedOutput output) {
        for (int i = 0; i < LIMIT + 1; i++) {
            DirectoryUser expiring = user("expiring" + i, "expiring" + i + "@example.gov");
            expiring.addCertificate(certificate(
                    "CN=expiring" + i, now.minus(Duration.ofDays(350)), now.plus(Duration.ofDays(2 + i)),
                    CertificateStatus.EXPIRING_SOON));
            save(expiring);
        }

        notificationService.digest();

        assertThat(addressedTo("expiring0@example.gov")).hasSize(1);
        assertThat(addressedTo("expiring" + LIMIT + "@example.gov")).isEmpty();
        assertThat(output.getOut()).contains("certificate(s) expire in the next");
    }

    /**
     * Expired certificates beside a current one are what a directory of any age is mostly
     * made of. They are left out by the read itself, so they use up none of the limit.
     */
    @Test
    void expiredCertificatesBesideACurrentOneDoNotCountAgainstTheLimit(CapturedOutput output) {
        DirectoryUser renewed = user("renewed", "renewed@example.gov");
        for (int i = 0; i < LIMIT + 2; i++) {
            renewed.addCertificate(certificate(
                    "CN=renewed,OU=" + i, now.minus(Duration.ofDays(800 + i)), now.minus(Duration.ofDays(400 + i)),
                    CertificateStatus.EXPIRED));
        }
        renewed.addCertificate(certificate(
                "CN=renewed", now.minus(Duration.ofDays(10)), now.plus(Duration.ofDays(355)),
                CertificateStatus.VALID));
        save(renewed);
        DirectoryUser lapsed = user("gone", "gone@example.gov");
        lapsed.addCertificate(certificate(
                "CN=gone", now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(5)),
                CertificateStatus.EXPIRED));
        save(lapsed);

        notificationService.digest();

        assertThat(addressedTo("renewed@example.gov")).isEmpty();
        assertThat(addressedTo("gone@example.gov")).hasSize(1);
        assertThat(output.getOut()).doesNotContain("Raise cert-alert.notifications.digest.scan-limit");
    }

    /**
     * The look-back: a certificate that expired longer ago than it is not read at all - so
     * a directory of old leftovers neither fills the read nor gets reported every day.
     */
    @Test
    void anExpiredCertificateOlderThanTheLookBackIsNotReported(CapturedOutput output) {
        for (int i = 0; i < LIMIT + 2; i++) {
            DirectoryUser old = user("old" + i, "old" + i + "@example.gov");
            old.addCertificate(certificate(
                    "CN=old" + i, now.minus(Duration.ofDays(500)), now.minus(Duration.ofDays(91 + i)),
                    CertificateStatus.EXPIRED));
            save(old);
        }
        DirectoryUser recent = user("recent", "recent@example.gov");
        recent.addCertificate(certificate(
                "CN=recent", now.minus(Duration.ofDays(400)), now.minus(Duration.ofDays(89)),
                CertificateStatus.EXPIRED));
        save(recent);

        double truncatedBefore = truncated();
        notificationService.digest();

        assertThat(addressedTo("recent@example.gov")).hasSize(1);
        assertThat(addressedTo("old0@example.gov")).isEmpty();
        assertThat(truncated()).isEqualTo(truncatedBefore);
        assertThat(output.getOut()).doesNotContain("Raise cert-alert.notifications.digest.scan-limit");
    }

    /**
     * Certificates already renewed - published again under the same subject, still live -
     * are left out by the query, so however many there are they use none of the limit and
     * cannot push one nobody has renewed out of the round-up.
     */
    @Test
    void renewedCertificatesDoNotCountAgainstTheLimit(CapturedOutput output) {
        for (int i = 0; i < LIMIT + 2; i++) {
            DirectoryUser renewed = user("renewed" + i, "renewed" + i + "@example.gov");
            renewed.addCertificate(certificate(
                    "CN=renewed" + i, now.minus(Duration.ofDays(360)), now.plus(Duration.ofDays(1 + i)),
                    CertificateStatus.EXPIRING_SOON));
            renewed.addCertificate(certificate(
                    "CN=renewed" + i, now.minus(Duration.ofDays(1)), now.plus(Duration.ofDays(364)),
                    CertificateStatus.VALID));
            save(renewed);
        }
        DirectoryUser forgotten = user("forgotten", "forgotten@example.gov");
        forgotten.addCertificate(certificate(
                "CN=forgotten", now.minus(Duration.ofDays(358)), now.plus(Duration.ofDays(20)),
                CertificateStatus.EXPIRING_SOON));
        save(forgotten);

        double truncatedBefore = truncated();
        NotificationService.DigestResult result = notificationService.digest();

        assertThat(addressedTo("forgotten@example.gov")).hasSize(1);
        assertThat(addressedTo("renewed0@example.gov")).isEmpty();
        assertThat(result.certificates()).isEqualTo(1);
        assertThat(truncated()).isEqualTo(truncatedBefore);
        assertThat(output.getOut()).doesNotContain("Raise cert-alert.notifications.digest.scan-limit");
    }

    private List<Notification> addressedTo(String address) {
        return notifications.findAll().stream()
                .filter(notification -> address.equals(notification.getRecipientAddress()))
                .toList();
    }

    private DirectoryUser user(String uid, String mail) {
        DirectoryUser user = new DirectoryUser("uid=" + uid + ",ou=people");
        user.setUid(uid);
        user.setDisplayName(uid);
        user.refreshIdentifiers(mail);
        return user;
    }

    private void save(DirectoryUser user) {
        user.markSynced(now);
        user.refreshCertificateSummary();
        users.save(user);
    }

    private CachedCertificate certificate(
            String subjectDn, Instant notBefore, Instant notAfter, CertificateStatus status) {

        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) (subjectDn + notBefore).hashCode())),
                "01",
                subjectDn,
                "CN=Example CA",
                notBefore,
                notAfter,
                "SHA256withRSA",
                "SHA-256",
                "RSA",
                2048,
                null,
                now);
        certificate.updateStatus(status, now);
        return certificate;
    }

    private double truncated() {
        io.micrometer.core.instrument.Counter counter = meters.find("ens.certalert.digest.truncated").counter();
        return counter == null ? 0 : counter.count();
    }
}
