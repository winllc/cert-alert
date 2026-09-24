package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.DirectoryUser;
import com.winllc.certalert.domain.RevocationMethod;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.repository.DirectoryUserRepository;
import com.winllc.certalert.service.DirectorySyncService;
import com.winllc.certalert.service.RevocationService;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestOcspResponder;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * An authority whose certificates say nothing about where to ask.
 *
 * <p>Neither extension: no authority information access, so no responder address, and no
 * CRL distribution point either. That is an ordinary internal CA - everything that will
 * ever validate one of its certificates already knows where its responder is, so it does
 * not carry the address around - and it is the deployment that
 * {@code cert-alert.revocation.default-ocsp-url} exists for.
 *
 * <p>The scheduled check used to be unable to use it. It worked from the cache alone, and
 * the cache holds a serial number but not the certificate - so it could ask a list, which
 * needs only the serial, and never a responder, which needs the certificate itself. Every
 * answer came back "the certificate names no CRL distribution point" whatever was
 * configured. Here the whole job runs: a directory is swept, the certificates are read back
 * out of it, and a real responder is asked and signs its answer.
 */
@SpringBootTest
@ActiveProfiles("test")
class RevocationWithoutEndpointsTest {

    private static final Instant NOW = Instant.now();

    private static EmbeddedDirectory directory;
    private static TestOcspResponder responder;
    private static TestCa ca;
    private static Path issuerDirectory;
    private static BigInteger revokedSerial;
    private static BigInteger goodSerial;

    @Autowired
    private RevocationService revocationService;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryUserRepository users;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeAll
    static void publishADirectory() throws IOException {
        ca = new TestCa("Example Silent CA");
        responder = new TestOcspResponder(ca);

        // The trust chain, which is what makes a responder answerable at all: OCSP names a
        // certificate by hashes of its issuer's name and key, and the leaf carries neither.
        issuerDirectory = Files.createTempDirectory("cert-alert-silent-issuers");
        Files.writeString(issuerDirectory.resolve("ca.pem"), ca.certificatePem());

        Instant from = NOW.minus(Duration.ofDays(10));
        Instant to = NOW.plus(Duration.ofDays(355));

        // Null, null: no distribution point and no responder address in either certificate.
        TestCa.Issued stolen = ca.issue("silent.stolen@example.gov", from, to, null, null);
        TestCa.Issued fine = ca.issue("silent.fine@example.gov", from, to, null, null);
        revokedSerial = stolen.serial();
        goodSerial = fine.serial();
        responder.revoke(revokedSerial, NOW.minus(Duration.ofDays(2)));

        directory = new EmbeddedDirectory();
        directory.addUser("silent-stolen", "Stolen Key", "silent.stolen@example.gov", stolen.der());
        directory.addUser("silent-fine", "Fine Person", "silent.fine@example.gov", fine.der());
    }

    @AfterAll
    static void stop() {
        if (directory != null) {
            directory.close();
        }
        if (responder != null) {
            responder.close();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.ldap.urls", () -> directory.url());
        registry.add("spring.ldap.base", () -> EmbeddedDirectory.BASE_DN);
        registry.add("cert-alert.ldap.user.search-base", () -> "ou=people");
        registry.add("cert-alert.ldap.server.search-base", () -> "ou=servers");
        registry.add("cert-alert.revocation.issuer-directory", () -> issuerDirectory.toString());
        registry.add("cert-alert.revocation.default-ocsp-url", responder::url);
    }

    @BeforeEach
    void cacheTheDirectory() {
        if (users.findByDn(dn("silent-stolen")).isEmpty()) {
            syncService.syncUsers();
        }
    }

    /** What the cache has to say about these on its own: nowhere to ask. */
    @Test
    void neitherCertificateNamesAnywhereToAsk() {
        CachedCertificate cached = certificateOf("silent-stolen", revokedSerial);

        assertThat(cached.getCrlUrls()).isEmpty();
        assertThat(cached.getOcspUrl()).isNull();
    }

    /**
     * The configured responder is asked anyway, and its answer is what the check records.
     * This is the whole feature: without it a directory issued by such a CA reports every
     * certificate unanswered for ever.
     */
    @Test
    void theConfiguredResponderIsAskedAndAnswers() {
        int before = responder.requestCount();

        revocationService.checkAll();

        assertThat(responder.requestCount()).as("the responder was actually asked").isGreaterThan(before);

        CachedCertificate revoked = certificateOf("silent-stolen", revokedSerial);
        assertThat(revoked.getRevocationStatus()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(revoked.getRevocationMethod()).isEqualTo(RevocationMethod.OCSP);
        assertThat(revoked.getRevokedAt()).isNotNull().isBefore(Instant.now());
        // Which responder said so, and that the certificate never named it.
        assertThat(revoked.getRevocationDetail())
                .contains(responder.url())
                .contains("(configured default)");
    }

    /** And the one the authority does not list comes back good rather than unknown. */
    @Test
    void theCertificateTheAuthorityDoesNotListIsGood() {
        revocationService.checkAll();

        CachedCertificate good = certificateOf("silent-fine", goodSerial);
        assertThat(good.getRevocationStatus()).isEqualTo(RevocationStatus.GOOD);
        assertThat(good.getRevocationMethod()).isEqualTo(RevocationMethod.OCSP);
        assertThat(good.getRevokedAt()).isNull();
    }

    private static String dn(String uid) {
        return "uid=" + uid + "," + EmbeddedDirectory.PEOPLE_DN;
    }

    private CachedCertificate certificateOf(String uid, BigInteger serial) {
        String hex = serial.toString(16);
        List<CachedCertificate> owned = transactionTemplate.execute(status -> {
            DirectoryUser user = users.findWithCertificatesById(
                            users.findByDn(dn(uid)).orElseThrow().getId())
                    .orElseThrow();
            return List.copyOf(user.getCertificates());
        });
        return owned.stream()
                .filter(certificate -> hex.equalsIgnoreCase(certificate.getSerialNumber()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No certificate with serial " + hex + " for " + uid));
    }
}
