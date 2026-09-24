package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.alert.CertificateAlert;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.CertificateStatus;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.KeyUsage;
import com.winllc.certalert.domain.Notification;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.Severity;
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
 * A person's pair is one thing to do something about.
 *
 * <p>PKI for people issues two certificates at a time - one that signs, one that is
 * encrypted to - within moments of each other, to the same name. They expire within moments
 * of each other too, and renewing means replacing both. So the round-up counts them once,
 * writes about them once, and names both serial numbers on the one line.
 */
@SpringBootTest(
        properties = {
            "cert-alert.notifications.email.enabled=true",
            "cert-alert.notifications.email.from=cert-alert@example.gov"
        })
@ActiveProfiles("test")
class CredentialPairTest {

    /** An hour of slack, so whole days counted from "now" read as the number meant. */
    private static final Duration DAYS_5 = Duration.ofDays(5).plusHours(1);

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private com.winllc.certalert.repository.CachedCertificateRepository certificates;

    private Instant now;

    @BeforeEach
    void clear() {
        notifications.deleteAll();
        users.deleteAll();
        now = Instant.now();
    }

    /** One line, one date, two serial numbers. */
    @Test
    void aPairIsOneEntryInTheRoundUp() {
        seedPair("pairish", "Pai Rish", "pairish@example.gov", DAYS_5, DAYS_5);

        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.messages()).hasSize(1);
        NotificationMailer.Rendered message = result.messages().getFirst();
        assertThat(message.subject()).isEqualTo("[cert-alert] 1 of your credential(s) expiring soon");
        assertThat(message.text())
                .contains("1 of your credential(s) expiring soon")
                .contains("Signing and encryption - 2 certificates")
                // Both halves are named, because both have to be replaced.
                .contains("serials ")
                .contains("Expires in 5 day(s)");
        // One line about the credential, not one per certificate.
        assertThat(message.text().split("\\n  \\* ")).hasSize(2);

        // And the same in the HTML: one row, saying what it covers.
        assertThat(message.html())
                .contains("Signing and encryption (2 certificates, renewed together)")
                .contains("Serials ");
        // Named once, so it is one row and not two that look alike.
        assertThat(message.html().split("CN=pairish@example.gov", -1))
                .as("one row for the credential")
                .hasSize(2);
    }

    /** And the page says the same: one credential, not two certificates. */
    @Test
    void thePageCountsTheCredentialOnce() {
        seedPair("counted", "Cou Nted", "counted@example.gov", DAYS_5, DAYS_5);

        notificationService.digest();

        List<Notification> theirs = notifications.findAll().stream()
                .filter(notification -> "counted@example.gov".equals(notification.getRecipientAddress()))
                .toList();
        assertThat(theirs).hasSize(1);
        assertThat(theirs.getFirst().getMessage())
                .contains("1 credential(s) expiring")
                .contains("(signing and encryption)")
                .doesNotContain("2 certificate(s)");
    }

    /**
     * The pair is only as good as its sooner half. Renewing one and not the other leaves
     * somebody able to sign and not to read, and the date that matters is the first one.
     */
    @Test
    void theCredentialExpiresWhenItsFirstHalfDoes() {
        seedPair("straddling", "Str Addling", "straddling@example.gov",
                Duration.ofDays(3).plusHours(1), Duration.ofDays(20).plusHours(1));

        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst().text()).contains("Expires in 3 day(s)");
    }

    /**
     * Two certificates of the same use and the same name are not a pair, whenever they were
     * issued - that is a renewal, and the older one is reported as replaced rather than
     * folded into a credential with its successor.
     */
    @Test
    void twoOfTheSameHalfAreNotAPair() {
        DirectoryUser twice = user("twice", "Tw Ice", "twice@example.gov");
        twice.addCertificate(signing("CN=twice@example.gov", now.minus(Duration.ofDays(300)),
                now.plus(DAYS_5)));
        twice.addCertificate(signing("CN=twice@example.gov", now.minus(Duration.ofDays(299)),
                now.plus(Duration.ofDays(6).plusHours(1))));
        save(twice);

        NotificationService.DigestResult result = notificationService.digest(true);

        // The older is replaced by the newer, so one credential of one certificate.
        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst().text())
                .contains("Expires in 6 day(s)")
                .doesNotContain("2 certificates");
    }

    /** Issued far apart, they are two credentials rather than one pair. */
    @Test
    void halvesIssuedFarApartAreNotOneCredential() {
        DirectoryUser apart = user("apart", "Ap Art", "apart@example.gov");
        apart.addCertificate(signing("CN=apart@example.gov", now.minus(Duration.ofDays(300)),
                now.plus(DAYS_5)));
        // A year later: a different issuance, whatever it pairs with.
        apart.addCertificate(encryption("CN=apart@example.gov", now.minus(Duration.ofDays(60)),
                now.plus(Duration.ofDays(6).plusHours(1))));
        save(apart);

        NotificationService.DigestResult result = notificationService.digest(true);

        assertThat(result.messages()).hasSize(1);
        assertThat(result.messages().getFirst().subject())
                .isEqualTo("[cert-alert] 2 of your credential(s) expiring soon");
    }

    /**
     * The alert path too. Both halves cross into EXPIRING_SOON within moments of each
     * other, because they were issued within moments of each other - and that is one thing
     * to tell somebody, not two identical messages with different serial numbers.
     */
    @Test
    void bothHalvesCrossingIsOneNotification() {
        seedPair("alerted", "Al Erted", "alerted@example.gov", DAYS_5, DAYS_5);
        DirectoryUser person = users.findByDn("uid=alerted,ou=people").orElseThrow();

        for (CachedCertificate certificate : certificates.findForUsers(List.of(person.getId()))) {
            notificationService.notifyAbout(CertificateAlert.from(
                    OwnerType.USER,
                    person.getId(),
                    person.getDisplayName(),
                    person.getDn(),
                    "alerted@example.gov",
                    certificate,
                    Severity.WARNING,
                    5L,
                    now));
        }

        assertThat(notifications.findAll().stream()
                        .filter(notification -> "alerted@example.gov".equals(notification.getRecipientAddress()))
                        .toList())
                .as("one credential, one notification")
                .hasSize(1);
    }

    /** Two separate credentials still produce two, which is the case that must not be lost. */
    @Test
    void twoCredentialsAreStillTwoNotifications() {
        DirectoryUser several = user("several", "Sev Eral", "several@example.gov");
        several.addCertificate(signing("CN=several@example.gov", now.minus(Duration.ofDays(300)),
                now.plus(DAYS_5)));
        several.addCertificate(signing("CN=several.alt@example.gov", now.minus(Duration.ofDays(300)),
                now.plus(DAYS_5)));
        save(several);

        DirectoryUser saved = users.findByDn("uid=several,ou=people").orElseThrow();
        for (CachedCertificate certificate : certificates.findForUsers(List.of(saved.getId()))) {
            notificationService.notifyAbout(CertificateAlert.from(
                    OwnerType.USER, saved.getId(), saved.getDisplayName(), saved.getDn(),
                    "several@example.gov", certificate, Severity.WARNING, 5L, now));
        }

        assertThat(notifications.findAll().stream()
                        .filter(notification -> "several@example.gov".equals(notification.getRecipientAddress()))
                        .toList())
                .hasSize(2);
    }

    private void seedPair(String uid, String name, String mail, Duration signingIn, Duration encryptionIn) {
        DirectoryUser person = user(uid, name, mail);
        Instant issued = now.minus(Duration.ofDays(300));
        person.addCertificate(signing("CN=" + mail, issued, now.plus(signingIn)));
        // A minute later, which is how the second half of one issuance reaches a directory.
        person.addCertificate(encryption("CN=" + mail, issued.plusSeconds(60), now.plus(encryptionIn)));
        save(person);
    }

    private CachedCertificate signing(String subjectDn, Instant notBefore, Instant notAfter) {
        CachedCertificate certificate = certificate(subjectDn, notBefore, notAfter);
        certificate.describeKeyUsage(List.of(KeyUsage.DIGITAL_SIGNATURE, KeyUsage.NON_REPUDIATION));
        return certificate;
    }

    private CachedCertificate encryption(String subjectDn, Instant notBefore, Instant notAfter) {
        CachedCertificate certificate = certificate(subjectDn, notBefore, notAfter);
        certificate.describeKeyUsage(List.of(KeyUsage.KEY_ENCIPHERMENT));
        return certificate;
    }

    private CachedCertificate certificate(String subjectDn, Instant notBefore, Instant notAfter) {
        CachedCertificate certificate = new CachedCertificate(
                String.format("%064x", Math.abs((long) (subjectDn + notBefore + notAfter).hashCode())),
                Integer.toHexString(Math.abs((subjectDn + notBefore).hashCode())),
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
        certificate.updateStatus(
                notAfter.isAfter(now) ? CertificateStatus.EXPIRING_SOON : CertificateStatus.EXPIRED, now);
        return certificate;
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
}
