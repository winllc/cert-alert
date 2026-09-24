package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * When the round-up stops writing about a certificate.
 *
 * <p>The thing that makes a round-up worth reading is that everything in it still needs
 * doing. A certificate somebody renewed last week does not, and going on about it for the
 * weeks until the old one lapses is how a round-up teaches people to ignore it.
 *
 * <p>The other way to be wrong is worse, so it is tested here too: a person holds more than
 * one certificate at a time, and treating the newest as standing in for the rest would mean
 * saying nothing about a credential that is genuinely running out.
 */
@SpringBootTest
@ActiveProfiles("test")
class NotificationSupersessionTest {

    @Autowired
    private NotificationService notificationService;

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

    /** The renewal case: same subject, published again. Nothing left for anybody to do. */
    @Test
    void aCertificateThatHasBeenReissuedIsNotWrittenAboutAgain() {
        DirectoryUser renewed = user("renewed", "Re Newed", "renewed@example.gov");
        renewed.addCertificate(certificate(
                "CN=renewed@example.gov",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON));
        // Published since, for the same name: what the CA does when it renews.
        renewed.addCertificate(certificate(
                "CN=renewed@example.gov", now, now.plus(Duration.ofDays(365)), CertificateStatus.VALID));
        save(renewed);

        NotificationService.DigestResult result = notificationService.digest();

        assertThat(addressedTo("renewed@example.gov")).isEmpty();
        assertThat(result.certificates()).as("the old one is not counted as reported either").isZero();
    }

    /**
     * Two certificates for two names, one newer than the other. Neither replaces anything,
     * so the one running out is still somebody's problem.
     */
    @Test
    void aNewerCertificateForAnotherNameSilencesNothing() {
        DirectoryUser both = user("paired", "Pai Red", "paired@example.gov");
        both.addCertificate(certificate(
                "CN=paired@example.gov,OU=signing",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON));
        // Issued more recently, and for something else: a key encipherment certificate
        // issued yesterday says nothing about a signing certificate expiring on Friday.
        both.addCertificate(certificate(
                "CN=paired@example.gov,OU=encryption",
                now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(20)),
                CertificateStatus.VALID));
        save(both);

        notificationService.digest();

        List<Notification> theirs = addressedTo("paired@example.gov");
        assertThat(theirs).hasSize(1);
        assertThat(theirs.getFirst().getMessage()).contains("2 certificate(s) expiring");
    }

    /**
     * An entry whose certificates have all lapsed is the one that most needs telling. Asking
     * whether something newer is standing in must not silence the case where nothing is.
     */
    @Test
    void anEntryWithNothingLeftStandingIsStillTold() {
        DirectoryUser lapsed = user("lapsed", "La Psed", "lapsed@example.gov");
        lapsed.addCertificate(certificate(
                "CN=lapsed@example.gov",
                now.minus(Duration.ofDays(400)),
                now.minus(Duration.ofDays(30)),
                CertificateStatus.EXPIRED));
        lapsed.addCertificate(certificate(
                "CN=lapsed@example.gov",
                now.minus(Duration.ofDays(365)),
                now.minus(Duration.ofDays(2)),
                CertificateStatus.EXPIRED));
        save(lapsed);

        notificationService.digest();

        assertThat(addressedTo("lapsed@example.gov")).hasSize(1);
    }

    /**
     * A server reads VALID when it publishes one good certificate, and the message agrees
     * with the page: its contacts are not written to about the one beside it.
     */
    @Test
    void aServerAlreadyPublishingAGoodCertificateHearsNothing() {
        DirectoryServer web = new DirectoryServer("cn=web01,ou=servers");
        web.setCommonName("web01");
        web.setServerPocs(List.of("ops@example.gov"));
        web.addCertificate(certificate(
                "CN=web01.example.gov",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(4)),
                CertificateStatus.EXPIRING_SOON));
        web.addCertificate(certificate(
                "CN=web01.example.gov", now, now.plus(Duration.ofDays(365)), CertificateStatus.VALID));
        web.markSynced(now);
        web.refreshCertificateSummary();
        servers.save(web);

        assertThat(web.getCertificateStatus()).isEqualTo(CertificateStatus.VALID);

        notificationService.digest();

        assertThat(addressedTo("ops@example.gov")).isEmpty();
    }

    /**
     * Email is off in the tests, which is the state a deployment is in when somebody asks
     * what turning it on would do. The rehearsal answers anyway, and says which it is.
     */
    @Test
    void aRehearsalAnswersBeforeEmailIsSwitchedOn() {
        DirectoryUser asking = user("asking", "As King", "asking@example.gov");
        asking.addCertificate(certificate(
                "CN=asking@example.gov",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON));
        save(asking);

        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.emailEnabled()).as("nothing would actually go out yet").isFalse();
        assertThat(result.emailsSent()).isEqualTo(1);
        assertThat(result.messages()).singleElement().satisfies(message -> {
            assertThat(message.to()).isEqualTo("asking@example.gov");
            assertThat(message.html()).contains("CN=asking@example.gov");
        });
        assertThat(notifications.count()).isZero();
    }

    private List<Notification> addressedTo(String address) {
        return notifications.findAll().stream()
                .filter(notification -> address.equals(notification.getRecipientAddress()))
                .toList();
    }

    private DirectoryUser user(String uid, String displayName, String mail) {
        DirectoryUser user = new DirectoryUser("uid=" + uid + ",ou=people");
        user.setUid(uid);
        user.setDisplayName(displayName);
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
}
