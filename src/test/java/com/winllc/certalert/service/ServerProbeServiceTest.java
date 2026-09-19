package com.winllc.certalert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.winllc.certalert.domain.AuditAction;
import com.winllc.certalert.domain.DirectoryServer;
import com.winllc.certalert.domain.OwnerType;
import com.winllc.certalert.domain.ProbeFinding;
import com.winllc.certalert.repository.DirectoryServerRepository;
import com.winllc.certalert.support.EmbeddedDirectory;
import com.winllc.certalert.support.TestCertificates;
import com.winllc.certalert.support.TestTlsServer;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
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

/**
 * Asking an endpoint what it is serving, against an endpoint that really serves it.
 *
 * <p>A real TLS server on a real port for each case, because the thing being tested is a
 * handshake: what a mocked socket would prove is that the mock was written to agree with
 * the code.
 *
 * <p>The directory publishes two certificates for this server - an old one and the renewal.
 * Which of them the endpoint presents is what each case varies, and it is the whole
 * question: a renewal recorded in the directory and never installed looks perfectly healthy
 * on every other page here.
 */
@SpringBootTest
@ActiveProfiles("test")
class ServerProbeServiceTest {

    private static final Instant NOW = Instant.now();

    private static EmbeddedDirectory directory;
    private static X509Certificate current;
    private static X509Certificate superseded;
    private static X509Certificate stranger;

    @Autowired
    private ServerProbeService probeService;

    @Autowired
    private DirectorySyncService syncService;

    @Autowired
    private DirectoryServerRepository serverRepository;

    @Autowired
    private AuditService auditService;

    @BeforeAll
    static void startDirectory() {
        directory = new EmbeddedDirectory();

        byte[] old = TestCertificates.der("localhost", NOW.minus(Duration.ofDays(400)), NOW.plus(Duration.ofDays(30)));
        byte[] renewed = TestCertificates.der("localhost", NOW.minus(Duration.ofDays(2)), NOW.plus(Duration.ofDays(363)));
        superseded = TestCertificates.certificateOf(old);
        current = TestCertificates.certificateOf(renewed);
        // A perfectly good certificate that this directory has never published.
        stranger = TestCertificates.certificate(
                "localhost", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)));

        // No port in the URL: the host is the entry's, and each case says which port.
        directory.addServer("probe01", "https://localhost", new String[] {"alice@example.gov"}, old, renewed);
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
    }

    @BeforeEach
    void cacheTheDirectory() {
        if (serverRepository.findByDn(dn()).isEmpty()) {
            syncService.syncServers();
        }
    }

    // --- what the probe is for -----------------------------------------------------------

    @Test
    void anEndpointServingTheLatestCertificateSaysSo() {
        try (TestTlsServer endpoint = serving(current)) {
            ServerProbeService.Result result = probeService.probe(serverId(), endpoint.port(), "alice");

            assertThat(result.reachable()).isTrue();
            assertThat(result.findings()).contains(ProbeFinding.SERVING_CURRENT);
            assertThat(result.hasProblem()).isFalse();
            assertThat(result.presented().sha256Fingerprint())
                    .isEqualTo(result.expected().sha256Fingerprint());
            assertThat(result.protocol()).startsWith("TLS");
            assertThat(result.port()).isEqualTo(endpoint.port());
        }
    }

    @Test
    void theRenewalThatWasNeverInstalledIsWhatThisFinds() {
        // The directory has both certificates; the endpoint is still on the old one. Every
        // other page here reads this server as healthy, because the renewal did happen.
        try (TestTlsServer endpoint = serving(superseded)) {
            ServerProbeService.Result result = probeService.probe(serverId(), endpoint.port(), "alice");

            assertThat(result.findings()).contains(ProbeFinding.SERVING_SUPERSEDED);
            assertThat(result.hasProblem()).isTrue();
            // And it says what should have been there, so somebody can go and install it.
            assertThat(result.expected().sha256Fingerprint())
                    .isNotEqualTo(result.presented().sha256Fingerprint())
                    .isEqualTo(TestCertificates.sha256(encoded(current)));
        }
    }

    @Test
    void aCertificateTheDirectoryHasNeverPublishedIsSaidToBeUnknown() {
        try (TestTlsServer endpoint = serving(stranger)) {
            ServerProbeService.Result result = probeService.probe(serverId(), endpoint.port(), "alice");

            assertThat(result.findings()).contains(ProbeFinding.NOT_PUBLISHED);
            assertThat(result.hasProblem()).isTrue();
        }
    }

    @Test
    void anExpiredCertificateIsReportedAlongsideWhichOneItIs() {
        X509Certificate lapsed = TestCertificates.certificate(
                "localhost", NOW.minus(Duration.ofDays(400)), NOW.minus(Duration.ofDays(1)));
        try (TestTlsServer endpoint = serving(lapsed)) {
            ServerProbeService.Result result = probeService.probe(serverId(), endpoint.port(), "alice");

            assertThat(result.findings()).contains(ProbeFinding.EXPIRED, ProbeFinding.NOT_PUBLISHED);
        }
    }

    @Test
    void aCertificateForSomewhereElseIsAMismatchRatherThanAFailure() {
        X509Certificate elsewhere = TestCertificates.certificate(
                "other.example.gov", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)));
        try (TestTlsServer endpoint = serving(elsewhere)) {
            ServerProbeService.Result result = probeService.probe(serverId(), endpoint.port(), "alice");

            // Read and reported rather than refused: a validating client would have hung up
            // before there was anything to say about it.
            assertThat(result.reachable()).isTrue();
            assertThat(result.findings()).contains(ProbeFinding.NAME_MISMATCH);
            assertThat(result.presented().subjectDn()).contains("other.example.gov");
        }
    }

    @Test
    void aSelfSignedCertificateIsStillReadAndStillCompared() {
        // Every certificate these tests mint is its own issuer, which is the point here:
        // the handshake trusts nothing, so an untrusted chain is data rather than an error.
        try (TestTlsServer endpoint = serving(current)) {
            ServerProbeService.Result result = probeService.probe(serverId(), endpoint.port(), "alice");

            assertThat(result.findings()).contains(ProbeFinding.SELF_SIGNED, ProbeFinding.SERVING_CURRENT);
            // Self-signed and therefore not missing an intermediate it could never send.
            assertThat(result.findings()).doesNotContain(ProbeFinding.NO_INTERMEDIATES);
        }
    }

    // --- when it does not work ------------------------------------------------------------

    @Test
    void anEndpointThatIsNotThereIsAnAnswerRatherThanAnError() {
        int closed;
        try (TestTlsServer endpoint = serving(current)) {
            closed = endpoint.port();
        }

        ServerProbeService.Result result = probeService.probe(serverId(), closed, "alice");

        assertThat(result.reachable()).isFalse();
        assertThat(result.error()).contains("localhost:" + closed);
        assertThat(result.presented()).isNull();
        // Still says what should have been there, which is the useful half of the answer.
        assertThat(result.expected()).isNotNull();
    }

    @Test
    void aPortThatIsNotAPortIsRefusedBeforeAnythingIsOpened() {
        assertThatThrownBy(() -> probeService.probe(serverId(), 0, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- the trail ------------------------------------------------------------------------

    @Test
    void everyProbeIsRecordedAgainstTheServerAndWhoAskedForIt() {
        try (TestTlsServer endpoint = serving(current)) {
            probeService.probe(serverId(), endpoint.port(), "carol");
        }

        assertThat(auditService.history(OwnerType.SERVER, serverId(), PageRequest.of(0, 20)))
                .anySatisfy(event -> {
                    assertThat(event.getAction()).isEqualTo(AuditAction.ENDPOINT_PROBED);
                    assertThat(event.getActor()).isEqualTo("carol");
                    assertThat(event.getSummary()).contains("Serving the current certificate");
                });
    }

    private static TestTlsServer serving(X509Certificate certificate) {
        return new TestTlsServer(certificate, TestCertificates.sharedKeyPair().getPrivate());
    }

    private static byte[] encoded(X509Certificate certificate) {
        try {
            return certificate.getEncoded();
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String dn() {
        return "cn=probe01," + EmbeddedDirectory.SERVERS_DN;
    }

    private Long serverId() {
        return serverRepository.findByDn(dn()).map(DirectoryServer::getId).orElseThrow();
    }
}
