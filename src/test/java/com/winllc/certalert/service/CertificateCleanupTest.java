package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.RevocationMethod;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.ldap.LdapCertificateStore;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * Deleting from the directory the certificates it should not still be publishing.
 *
 * <p>Against a real directory, because the whole feature is one LDAP modify: the value
 * being removed is the DER of a certificate, and a test that stubbed the directory would
 * prove only that the stub agreed with the code about what bytes to send.
 *
 * <p>What is checked after each run is the directory itself, not the cache. The cache is
 * derived from it, and a cleanup that emptied the index while leaving the entry publishing
 * a revoked certificate would be the exact failure this has to rule out.
 */
@SpringBootTest
@ActiveProfiles("test")
class CertificateCleanupTest {

    private static final Instant NOW = Instant.now();

    private static EmbeddedDirectory directory;

    @Autowired
    private CertificateCleanupService cleanupService;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private LdapCertificateStore store;

    @Autowired
    private AuditService audit;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();
    }

    @AfterAll
    static void stopDirectory() {
        if (directory != null) {
            directory.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.ldap.certificate-cleanup.enabled", () -> "true");
        registry.add("cert-alert.ldap.certificate-cleanup.expired-after", () -> "90d");
        registry.add("cert-alert.ldap.certificate-cleanup.revoked-after", () -> "7d");
    }

    /** Every person this class makes, so each case starts from an empty directory. */
    private static final List<String> UIDS =
            List.of("lapsed", "recent", "compromised", "both", "untouched", "audited", "vanished");

    /**
     * A fresh directory each time, because the subject of every case is what the directory
     * is left holding - and what a case that ran before it left there would be counted in
     * the run as well as asserted on.
     */
    @BeforeEach
    void reset() {
        users.deleteAll();
        UIDS.forEach(CertificateCleanupTest::removeFromDirectory);
    }

    private static void removeFromDirectory(String uid) {
        try {
            directory.delete(dn(uid));
        } catch (RuntimeException e) {
            // Not there, which is the usual case and the one being arranged for.
        }
    }

    // --- what it removes ------------------------------------------------------------------

    @Test
    void aCertificateFinishedWithLongerThanTheWindowIsDeletedFromTheEntry() {
        byte[] ancient = TestCertificates.der("old", NOW.minus(Duration.ofDays(800)), NOW.minus(Duration.ofDays(200)));
        byte[] current = TestCertificates.der("new", NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(355)));
        String dn = add("lapsed", ancient, current);
        syncService.syncUsers();

        CertificateCleanupService.Result result = cleanupService.run();

        assertThat(result.removed()).isEqualTo(1);
        // The directory is what was changed, so the directory is what is asked.
        assertThat(published(dn)).containsExactly(TestCertificates.sha256(current));
        assertThat(cached("lapsed")).hasSize(1);
    }

    @Test
    void aCertificateInsideTheWindowIsLeftAlone() {
        byte[] recentlyExpired =
                TestCertificates.der("recent", NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(3)));
        String dn = add("recent", recentlyExpired);
        syncService.syncUsers();

        assertThat(cleanupService.run().removed()).isZero();
        // Expired, and not finished with: a renewal that ran late must not find the old
        // certificate already deleted out from under it.
        assertThat(published(dn)).hasSize(1);
    }

    @Test
    void aRevokedCertificateGoesWhateverItsDatesSay() {
        byte[] revoked = TestCertificates.der("revoked", NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(355)));
        byte[] fine = TestCertificates.der("fine", NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(355)));
        String dn = add("compromised", revoked, fine);
        syncService.syncUsers();
        markRevoked(TestCertificates.sha256(revoked), NOW.minus(Duration.ofDays(30)));

        CertificateCleanupService.Result result = cleanupService.run();

        // Perfectly valid by its dates, and published by a directory that should not be
        // publishing it - which is the case this feature exists for.
        assertThat(result.removed()).isEqualTo(1);
        assertThat(published(dn)).containsExactly(TestCertificates.sha256(fine));
    }

    @Test
    void bothKindsGoInOneModifyWhenOneEntryHasBoth() {
        byte[] ancient = TestCertificates.der("a", NOW.minus(Duration.ofDays(800)), NOW.minus(Duration.ofDays(300)));
        byte[] revoked = TestCertificates.der("b", NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(355)));
        byte[] fine = TestCertificates.der("c", NOW.minus(Duration.ofDays(10)), NOW.plus(Duration.ofDays(355)));
        String dn = add("both", ancient, revoked, fine);
        syncService.syncUsers();
        markRevoked(TestCertificates.sha256(revoked), NOW.minus(Duration.ofDays(30)));

        CertificateCleanupService.Result result = cleanupService.run();

        assertThat(result.removed()).isEqualTo(2);
        assertThat(result.entries()).isEqualTo(1);
        assertThat(published(dn)).containsExactly(TestCertificates.sha256(fine));
    }

    // --- what it does not do ----------------------------------------------------------------

    @Test
    void thePreviewCountsWhatWouldGoAndTakesNothing() {
        byte[] ancient = TestCertificates.der("off", NOW.minus(Duration.ofDays(800)), NOW.minus(Duration.ofDays(300)));
        String dn = add("untouched", ancient);
        syncService.syncUsers();

        CertificateCleanupService.Preview preview = cleanupService.preview();

        assertThat(preview.enabled()).isTrue();
        assertThat(preview.expired()).isEqualTo(1);
        assertThat(preview.wouldRemove()).isEqualTo(1);
        // And the entry still has it, which is the whole point of a preview.
        assertThat(published(dn)).hasSize(1);
    }

    /**
     * An entry deleted between the sweep and the cleanup. Not a refusal: reporting it as
     * one would send somebody looking through access control for a reason that is not
     * there.
     */
    @Test
    void anEntryThatIsNoLongerThereIsNotARefusal() {
        byte[] ancient = TestCertificates.der("missing",
                NOW.minus(Duration.ofDays(800)), NOW.minus(Duration.ofDays(300)));
        add("vanished", ancient);
        syncService.syncUsers();
        directory.delete(dn("vanished"));

        CertificateCleanupService.Result result = cleanupService.run();

        assertThat(result.missing()).isEqualTo(1);
        assertThat(result.refused()).isZero();
        // What becomes of an entry the directory has stopped publishing is the prune's
        // decision, not this job's: it removes certificates, not entries.
        assertThat(cached("vanished")).hasSize(1);
    }

    /**
     * The default that matters most here. This is the only thing in the application that
     * deletes from the directory, and an upgrade must never start doing it because a new
     * job appeared.
     */
    @Test
    void itIsOffUnlessSomebodyTurnsItOn() {
        assertThat(new com.winllc.certalert.ldap.LdapProperties().getCertificateCleanup().isEnabled())
                .isFalse();
    }

    @Test
    void everyRemovalIsRecordedAgainstTheEntryItWasTakenFrom() {
        byte[] ancient = TestCertificates.der("audited",
                NOW.minus(Duration.ofDays(800)), NOW.minus(Duration.ofDays(300)));
        add("audited", ancient);
        syncService.syncUsers();
        Long id = users.findByDn(dn("audited")).orElseThrow().getId();

        cleanupService.run();

        assertThat(audit.history(OwnerType.USER, id, PageRequest.of(0, 20)))
                .anySatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo(AuditAction.CERTIFICATE_DELETED);
                    assertThat(event.getActor()).isEqualTo("certificate cleanup");
                    assertThat(event.getSummary()).contains("expired on");
                    assertThat(event.getCertificateFingerprint()).isEqualTo(TestCertificates.sha256(ancient));
                });
    }

    @Test
    void oneAlreadyGoneFromTheDirectoryIsDroppedFromTheCacheWithoutComplaint() {
        byte[] ancient = TestCertificates.der("vanished",
                NOW.minus(Duration.ofDays(800)), NOW.minus(Duration.ofDays(300)));
        String dn = add("vanished", ancient);
        syncService.syncUsers();
        // Somebody got there first, between the sweep and the cleanup.
        directory.replaceCertificates(dn);

        CertificateCleanupService.Result result = cleanupService.run();

        assertThat(result.removed()).isZero();
        assertThat(result.alreadyGone()).isEqualTo(1);
        assertThat(result.refused()).isZero();
        assertThat(cached("vanished")).isEmpty();
    }

    // --- helpers ------------------------------------------------------------------------------

    private static String dn(String uid) {
        return "uid=" + uid + "," + EmbeddedDirectory.PEOPLE_DN;
    }

    private String add(String uid, byte[]... certificates) {
        return directory.addUser(uid, uid + " Person", uid + "@example.gov", certificates);
    }

    /** The fingerprints of what the directory is holding now. */
    private List<String> published(String dn) {
        return store.read(dn, "userCertificate").stream().map(TestCertificates::sha256).toList();
    }

    private List<CachedCertificate> cached(String uid) {
        return transactionTemplate.execute(status -> {
            DirectoryUser user = users.findWithCertificatesById(
                            users.findByDn(dn(uid)).orElseThrow().getId())
                    .orElseThrow();
            return List.copyOf(user.getCertificates());
        });
    }

    /** As the revocation check would have left it. */
    private void markRevoked(String fingerprint, Instant checkedAt) {
        transactionTemplate.executeWithoutResult(status -> {
            DirectoryUser user = users.findAll().stream()
                    .flatMap(u -> users.findWithCertificatesById(u.getId()).stream())
                    .filter(u -> u.getCertificates().stream()
                            .anyMatch(c -> fingerprint.equals(c.getSha256Fingerprint())))
                    .findFirst()
                    .orElseThrow();
            user.getCertificates().stream()
                    .filter(c -> fingerprint.equals(c.getSha256Fingerprint()))
                    .forEach(c -> c.recordRevocation(
                            RevocationStatus.REVOKED,
                            RevocationMethod.CRL,
                            checkedAt,
                            "KEY_COMPROMISE",
                            "CRL from http://crl.example.gov/ca.crl",
                            checkedAt));
            users.save(user);
        });
    }
}
