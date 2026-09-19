package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.NotificationKind;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationMethod;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.repository.NotificationRepository;
import com.winllc.certalert.service.AuditService;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.RevocationService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestCertificates;
import com.winllc.certalert.support.TestHttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Asking a real authority about real certificates.
 *
 * <p>A throwaway CA issues certificates that name where to ask about themselves, publishes
 * a signed CRL over HTTP at exactly that address, and revokes one of them. Nothing here is
 * stubbed, because every part of this is a format: the distribution point is an extension
 * that has to be read out of the encoding, the list is a signed structure that has to be
 * verified against the issuer, and the serial has to be found in it.
 */
@SpringBootTest
@ActiveProfiles("test")
class RevocationServiceTest {

    private static final Instant NOW = Instant.now();
    private static final String CRL_PATH = "/example.crl";

    private static EmbeddedDirectory directory;
    private static TestHttpServer publisher;
    private static TestCa ca;
    private static Path issuerDirectory;
    private static BigInteger revokedSerial;

    @Autowired
    private RevocationService revocationService;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private AuditService audit;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void publishADirectoryAndACrl() throws IOException {
        publisher = new TestHttpServer();
        ca = new TestCa("Example Revocation CA");

        // The application is given the CA's certificate, which is what lets it tell the
        // authority's own list from whatever answered the URL.
        issuerDirectory = Files.createTempDirectory("cert-alert-issuers");
        Files.writeString(issuerDirectory.resolve("ca.pem"), ca.certificatePem());

        String crlUrl = publisher.url(CRL_PATH);
        Instant from = NOW.minus(Duration.ofDays(10));
        Instant to = NOW.plus(Duration.ofDays(355));

        // A person's pair, with the signing half revoked - a stolen signing key being the
        // ordinary reason for one half of a pair to go and not the other.
        TestCa.Issued signing = ca.issueSigning("stolen.key@example.gov", from, to, crlUrl);
        TestCa.Issued encryption = ca.issueEncryption("stolen.key@example.gov", from, to, crlUrl);
        revokedSerial = signing.serial();

        TestCa.Issued goodSigning = ca.issueSigning("fine.person@example.gov", from, to, crlUrl);
        TestCa.Issued goodEncryption = ca.issueEncryption("fine.person@example.gov", from, to, crlUrl);

        // Issued before the current pair and revoked since. Superseded, so not asked about.
        TestCa.Issued lastYear = ca.issueSigning(
                "renewed.person@example.gov",
                NOW.minus(Duration.ofDays(400)),
                NOW.plus(Duration.ofDays(30)),
                crlUrl);
        TestCa.Issued thisYear = ca.issueSigning("renewed.person@example.gov", from, to, crlUrl);

        publisher.put(CRL_PATH, ca.crl(
                NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(1)),
                Map.of(
                        signing.serial(), NOW.minus(Duration.ofDays(2)),
                        lastYear.serial(), NOW.minus(Duration.ofDays(30)))));

        directory = new EmbeddedDirectory();
        directory.addUser("stolen", "Stolen Key", "stolen.key@example.gov", signing.der(), encryption.der());
        directory.addUser("fine", "Fine Person", "fine.person@example.gov", goodSigning.der(), goodEncryption.der());
        directory.addUser("renewed", "Renewed Person", "renewed.person@example.gov",
                lastYear.der(), thisYear.der());
        // A certificate from before any of this, naming nowhere to ask.
        directory.addUser("silent", "Silent Certificate", "silent@example.gov",
                TestCertificates.der("silent@example.gov", from, to));
    }

    @AfterAll
    static void stop() {
        if (directory != null) {
            directory.close();
        }
        if (publisher != null) {
            publisher.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.revocation.issuer-directory", () -> issuerDirectory.toString());
    }

    @BeforeEach
    void cacheTheDirectory() {
        if (users.findByDn(dn("stolen")).isEmpty()) {
            syncService.syncUsers();
        }
    }

    // --- what the check finds --------------------------------------------------------------

    @Test
    void aRevokedCertificateIsFoundAndSaidToBeRevoked() {
        revocationService.checkAll();

        CachedCertificate revoked = certificateOf("stolen", revokedSerial);
        assertThat(revoked.getRevocationStatus()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(revoked.getRevocationMethod()).isEqualTo(RevocationMethod.CRL);
        assertThat(revoked.getRevokedAt()).isNotNull().isBefore(Instant.now());
        assertThat(revoked.getRevocationReason()).isEqualTo("KEY_COMPROMISE");
        // Which list said so, and that it was provably the authority's own.
        assertThat(revoked.getRevocationDetail())
                .contains(publisher.url(CRL_PATH))
                .doesNotContain("not verified");
    }

    @Test
    void theOtherHalfOfThatPairIsNotRevokedAndSaysSo() {
        revocationService.checkAll();

        // Same person, same authority, same list - and only one of the two is on it.
        List<CachedCertificate> pair = certificatesOf("stolen");
        assertThat(pair).hasSize(2);
        assertThat(pair.stream().filter(CachedCertificate::isRevoked)).hasSize(1);
        assertThat(pair.stream().filter(c -> c.getRevocationStatus() == RevocationStatus.GOOD)).hasSize(1);
    }

    @Test
    void aCertificateTheAuthorityDoesNotListIsGood() {
        revocationService.checkAll();

        assertThat(certificatesOf("fine"))
                .hasSize(2)
                .allSatisfy(certificate -> {
                    assertThat(certificate.getRevocationStatus()).isEqualTo(RevocationStatus.GOOD);
                    assertThat(certificate.getRevocationCheckedAt()).isNotNull();
                });
    }

    /**
     * Asking about certificates an entry has already replaced would multiply the work by
     * however long the directory has been running, and an authority revoking one that is
     * no longer in use is not news.
     */
    @Test
    void aSupersededCertificateIsNotAskedAbout() {
        revocationService.checkAll();

        List<CachedCertificate> held = certificatesOf("renewed");
        CachedCertificate superseded = held.stream()
                .min(java.util.Comparator.comparing(CachedCertificate::getNotBefore))
                .orElseThrow();
        CachedCertificate current = held.stream()
                .max(java.util.Comparator.comparing(CachedCertificate::getNotBefore))
                .orElseThrow();

        // It is on the list, and it was never asked about.
        assertThat(superseded.getRevocationStatus()).isEqualTo(RevocationStatus.NOT_CHECKED);
        assertThat(current.getRevocationStatus()).isEqualTo(RevocationStatus.GOOD);
    }

    /**
     * Not GOOD. An unanswered question is not a negative answer, and recording it as one is
     * how a revoked certificate stays in service.
     */
    @Test
    void aCertificateThatNamesNowhereToAskIsUnknownRatherThanGood() {
        revocationService.checkAll();

        CachedCertificate silent = certificatesOf("silent").getFirst();
        assertThat(silent.getRevocationStatus()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(silent.getRevocationDetail()).contains("no CRL distribution point");
    }

    @Test
    void theListIsFetchedOnceHoweverManyCertificatesItAnswersFor() {
        int before = publisher.requestCount();

        revocationService.checkAll();

        // Seven certificates across four entries, one authority, one download.
        assertThat(publisher.requestCount() - before).isEqualTo(1);
    }

    // --- who hears about it ------------------------------------------------------------------

    @Test
    void whoeverHoldsTheRevokedCertificateIsToldAndItIsRecorded() {
        revocationService.checkAll();

        Long userId = users.findByDn(dn("stolen")).orElseThrow().getId();
        assertThat(audit.history(OwnerType.USER, userId, PageRequest.of(0, 20)))
                .anySatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo(AuditAction.CERTIFICATE_REVOKED);
                    assertThat(event.getActor()).isEqualTo("revocation");
                    assertThat(event.getSummary()).contains("Revoked");
                });
        assertThat(notifications.findAll().stream()
                        .filter(notification -> notification.getKind() == NotificationKind.CERTIFICATE_REVOKED))
                .isNotEmpty();
    }

    @Test
    void aSecondRunDoesNotTellEverybodyAgain() {
        revocationService.checkAll();
        long after = revoked(notifications.findAll());

        revocationService.checkAll();

        // Still revoked, and still the same notification: telling people nightly about a
        // revocation they already know about is how people learn to ignore it.
        assertThat(revoked(notifications.findAll())).isEqualTo(after);
    }

    // --- helpers -----------------------------------------------------------------------------

    private static long revoked(List<com.winllc.certalert.domain.Notification> all) {
        return all.stream()
                .filter(notification -> notification.getKind() == NotificationKind.CERTIFICATE_REVOKED)
                .count();
    }

    private static String dn(String uid) {
        return "uid=" + uid + "," + EmbeddedDirectory.PEOPLE_DN;
    }

    private List<CachedCertificate> certificatesOf(String uid) {
        return transactionTemplate.execute(status -> {
            DirectoryUser user = users.findWithCertificatesById(
                            users.findByDn(dn(uid)).orElseThrow().getId())
                    .orElseThrow();
            return List.copyOf(user.getCertificates());
        });
    }

    private CachedCertificate certificateOf(String uid, BigInteger serial) {
        String hex = serial.toString(16);
        return certificatesOf(uid).stream()
                .filter(certificate -> hex.equalsIgnoreCase(certificate.getSerialNumber()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No certificate with serial " + hex + " for " + uid));
    }
}
