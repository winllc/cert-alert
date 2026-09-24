package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.KeyUsage;
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
        assertThat(theirs.getFirst().getMessage()).contains("2 credential(s) expiring");
    }

    /**
     * A person's two certificates carry the same subject: PKI for people issues a signing
     * certificate and a key encipherment one to the same name, minutes apart. Neither
     * replaces the other - they are one credential in two halves - and the round-up has to
     * name both, because renewing one and not the other is the ordinary way to end up half
     * expired.
     */
    @Test
    void theTwoHalvesOfAPairDoNotReplaceEachOther() {
        DirectoryUser paired = user("holder", "Hol Der", "holder@example.gov");
        CachedCertificate signing = certificate(
                "CN=holder@example.gov",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON);
        signing.describeKeyUsage(List.of(KeyUsage.DIGITAL_SIGNATURE, KeyUsage.NON_REPUDIATION));
        // Issued a minute later, as the second half of one issuance is.
        CachedCertificate encryption = certificate(
                "CN=holder@example.gov",
                now.minus(Duration.ofDays(360)).plusSeconds(60),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON);
        encryption.describeKeyUsage(List.of(KeyUsage.KEY_ENCIPHERMENT));
        paired.addCertificate(signing);
        paired.addCertificate(encryption);
        save(paired);

        notificationService.digest();

        List<Notification> theirs = addressedTo("holder@example.gov");
        assertThat(theirs).hasSize(1);
        // One credential, named as the pair it is: two certificates, one thing to do.
        assertThat(theirs.getFirst().getMessage())
                .contains("1 credential(s) expiring")
                .contains("(signing and encryption)");
    }

    /** And renewing one half replaces that half, and only that half. */
    @Test
    void renewingOneHalfLeavesTheOtherStillReported() {
        DirectoryUser half = user("halfway", "Half Way", "halfway@example.gov");
        CachedCertificate oldSigning = certificate(
                "CN=halfway@example.gov",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON);
        oldSigning.describeKeyUsage(List.of(KeyUsage.DIGITAL_SIGNATURE));
        CachedCertificate newSigning = certificate(
                "CN=halfway@example.gov", now, now.plus(Duration.ofDays(365)), CertificateStatus.VALID);
        newSigning.describeKeyUsage(List.of(KeyUsage.DIGITAL_SIGNATURE));
        CachedCertificate encryption = certificate(
                "CN=halfway@example.gov",
                now.minus(Duration.ofDays(360)).plusSeconds(60),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON);
        encryption.describeKeyUsage(List.of(KeyUsage.KEY_ENCIPHERMENT));
        half.addCertificate(oldSigning);
        half.addCertificate(newSigning);
        half.addCertificate(encryption);
        save(half);

        notificationService.digest();

        List<Notification> theirs = addressedTo("halfway@example.gov");
        assertThat(theirs).hasSize(1);
        // The encryption half only: the signing half has been renewed and is nobody's
        // problem, and saying "2" here would be the noise the whole rule exists to stop.
        assertThat(theirs.getFirst().getMessage())
                .contains("1 credential(s) expiring")
                .doesNotContain("(signing and encryption)");
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

    /**
     * A rehearsal that builds nothing says why. "No messages" is the answer to half a dozen
     * different questions - a window nothing falls inside, a directory that has renewed
     * everything, contacts with no address published - and which of them it is decides
     * whether anybody has anything to do.
     */
    @Test
    void anEmptyRehearsalSaysWhyItIsEmpty() {
        // Nothing in the database at all: nothing is expiring.
        NotificationService.DigestResult empty = notificationService.digest(true);
        assertThat(empty.messages()).isEmpty();
        assertThat(empty.note()).contains("Nothing is expiring in the next");

        // Something expiring, and it has already been published again.
        DirectoryUser renewed = user("explained", "Ex Plained", "explained@example.gov");
        renewed.addCertificate(certificate(
                "CN=explained@example.gov",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON));
        renewed.addCertificate(certificate(
                "CN=explained@example.gov", now, now.plus(Duration.ofDays(365)), CertificateStatus.VALID));
        save(renewed);

        NotificationService.DigestResult replaced = notificationService.digest(true);
        assertThat(replaced.messages()).isEmpty();
        assertThat(replaced.note()).contains("already been published again");
    }

    /** And a rehearsal that does build something has nothing to explain. */
    @Test
    void aRehearsalThatBuildsSomethingSaysNothingAboutWhyNot() {
        DirectoryUser told = user("noted", "No Ted", "noted@example.gov");
        told.addCertificate(certificate(
                "CN=noted@example.gov",
                now.minus(Duration.ofDays(360)),
                now.plus(Duration.ofDays(5)),
                CertificateStatus.EXPIRING_SOON));
        save(told);

        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.messages()).isNotEmpty();
        assertThat(result.note()).isNull();
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
