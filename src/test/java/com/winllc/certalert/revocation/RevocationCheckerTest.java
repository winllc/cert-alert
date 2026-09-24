package com.winllc.certalert.revocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.winllc.certalert.config.RevocationProperties;
import com.winllc.certalert.config.RiskProperties;
import com.winllc.certalert.domain.CachedCertificate;
import com.winllc.certalert.domain.RevocationStatus;
import com.winllc.certalert.service.CertificateParser;
import com.winllc.certalert.support.TestCa;
import com.winllc.certalert.support.TestHttpServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What the check does when the answer is not simply yes or no.
 *
 * <p>The interesting cases are all about not knowing: a distribution point that does not
 * answer, a list that cannot be shown to be the authority's own. None of them may come back
 * as {@link RevocationStatus#GOOD}, because the difference between "the authority does not
 * list this" and "nobody could be asked" is the whole value of the check.
 */
class RevocationCheckerTest {

    private static final Instant NOW = Instant.now();
    private static final String CRL_PATH = "/ca.crl";

    private final CertificateParser parser = new CertificateParser(new RiskProperties());
    private final TestCa ca = new TestCa("Example Verification CA");

    private TestHttpServer publisher;
    private RevocationProperties properties;

    @BeforeEach
    void publish() {
        publisher = new TestHttpServer();
        properties = new RevocationProperties();
    }

    @AfterEach
    void stop() {
        publisher.close();
    }

    @Test
    void aListFromAnUnknownIssuerIsUsedAndSaidToBeUnverified() {
        // No issuer certificates configured, which is the out-of-the-box state: the list
        // is worth more than no answer, and the result says what it could not establish.
        CachedCertificate certificate = issueAndRevoke();

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(outcome.detail()).contains("signature not verified");
    }

    @Test
    void andIsRefusedWhereThatIsNotGoodEnough() {
        CachedCertificate certificate = issueAndRevoke();
        properties.setAllowUnverifiedCrl(false);

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        // Not GOOD, and not REVOKED either: an unverifiable list answers nothing.
        assertThat(outcome.status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(outcome.detail()).contains("could not be verified");
    }

    @Test
    void aListFromTheKnownIssuerIsVerified() throws IOException {
        CachedCertificate certificate = issueAndRevoke();

        RevocationChecker.Outcome outcome = checker(issuerDirectory()).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.REVOKED);
        assertThat(outcome.detail()).doesNotContain("not verified");
        assertThat(outcome.reason()).isEqualTo("KEY_COMPROMISE");
    }

    @Test
    void aDistributionPointThatDoesNotAnswerLeavesTheQuestionOpen() {
        CachedCertificate certificate = parse(ca.issue(
                        "gone", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(364)),
                        publisher.url(CRL_PATH), null)
                .der());
        // Nothing published at that path: a 404, which is what a distribution point that
        // has moved looks like.

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(outcome.detail()).contains("No distribution point answered");
    }

    @Test
    void theSecondDistributionPointIsTriedWhenTheFirstFails() {
        // A certificate naming two, the first of them dead. Issuers publish several for
        // exactly this reason, so trying only the first would throw the answer away.
        TestCa.Issued issued = ca.issue(
                "two-points",
                NOW.minus(Duration.ofDays(1)),
                NOW.plus(Duration.ofDays(364)),
                publisher.url("/missing.crl"),
                null);
        CachedCertificate certificate = parse(issued.der());
        certificate.describeRevocationEndpoints(
                java.util.List.of(publisher.url("/missing.crl"), publisher.url(CRL_PATH)),
                null,
                certificate.getAuthorityKeyId());
        publisher.put(CRL_PATH, ca.emptyCrl());

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.GOOD);
        assertThat(outcome.detail()).contains(CRL_PATH);
    }

    // --- the configured responder ----------------------------------------------------------

    /**
     * Where to ask is carried by the certificate and by nothing else, and an internal CA
     * issuing inside one network often leaves the extension out - everything that will
     * ever validate the certificate already knows where the responder is. The configured
     * default is how this application is told.
     */
    @Test
    void aCertificateNamingNoResponderFallsBackToTheConfiguredOne() {
        CachedCertificate certificate = parse(ca.issue(
                "no-aia", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null, null).der());

        assertThat(certificate.getOcspUrl()).as("nothing in the certificate to go on").isNull();
        assertThat(RevocationChecker.responderFor(certificate, "http://ocsp.example.gov"))
                .isEqualTo("http://ocsp.example.gov");
    }

    /**
     * And the issuer saying where to ask outranks the setting. A deployment reading more
     * than one authority has at most one configured address that is right for a given
     * certificate, so the one in the certificate wins wherever there is one.
     */
    @Test
    void butTheCertificatesOwnResponderWins() {
        CachedCertificate certificate = parse(ca.issue(
                "with-aia", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)),
                null, "http://ocsp.issuer.example.gov").der());

        assertThat(RevocationChecker.responderFor(certificate, "http://ocsp.example.gov"))
                .isEqualTo("http://ocsp.issuer.example.gov");
    }

    @Test
    void andWithNeitherThereIsNowhereToAsk() {
        CachedCertificate certificate = parse(ca.issue(
                "no-aia", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null, null).der());

        assertThat(RevocationChecker.responderFor(certificate, null)).isNull();
        assertThat(RevocationChecker.responderFor(certificate, "   ")).isNull();
    }

    /**
     * A result from the configured default says so. "The responder said it is good" means
     * something different when the certificate never named that responder, and whoever
     * reads the result is owed the difference.
     */
    @Test
    void aResultFromTheConfiguredResponderSaysWhereItCameFrom() {
        CachedCertificate named = parse(ca.issue(
                "with-aia", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)),
                null, "http://ocsp.issuer.example.gov").der());
        CachedCertificate silent = parse(ca.issue(
                "no-aia", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null, null).der());

        assertThat(RevocationChecker.describeResponder(named, "http://ocsp.issuer.example.gov"))
                .isEqualTo("OCSP responder http://ocsp.issuer.example.gov");
        assertThat(RevocationChecker.describeResponder(silent, "http://ocsp.example.gov"))
                .isEqualTo("OCSP responder http://ocsp.example.gov (configured default)");
    }

    /**
     * The same bargain for the list, and the cheaper one to take: a CA that leaves out the
     * responder address usually leaves out the distribution point too, and one list answers
     * for every certificate it ever issued where a responder is one request each.
     */
    @Test
    void aCertificateNamingNoDistributionPointFallsBackToTheConfiguredOne() {
        CachedCertificate certificate = parse(ca.issue(
                "no-crldp", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null, null).der());
        publisher.put(CRL_PATH, ca.emptyCrl());
        properties.setDefaultCrlUrl(publisher.url(CRL_PATH));

        assertThat(certificate.getCrlUrls()).as("nothing in the certificate to go on").isEmpty();

        RevocationChecker.Outcome outcome = checker(null).check(certificate, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.GOOD);
        // And says it was a guess, for the same reason the responder result does.
        assertThat(outcome.detail()).contains(CRL_PATH).contains("(configured default)");
    }

    /** The certificate's own points win, and all of them are still tried in its order. */
    @Test
    void butTheCertificatesOwnDistributionPointWins() {
        CachedCertificate certificate = parse(ca.issue(
                "with-crldp", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)),
                publisher.url(CRL_PATH), null).der());

        assertThat(RevocationChecker.distributionPointsFor(certificate, "http://crl.example.gov/other.crl"))
                .containsExactly(publisher.url(CRL_PATH));
    }

    /**
     * A responder that is configured and could not be asked says which piece was missing.
     * Reporting "the certificate names no CRL distribution point" against a certificate
     * whose responder the deployment configured itself is how a check looks broken while
     * behaving exactly as written.
     */
    @Test
    void aResponderThatCouldNotBeAskedSaysWhyRatherThanBlamingTheCertificate() throws IOException {
        CachedCertificate certificate = parse(ca.issue(
                "no-aia", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null, null).der());
        properties.setDefaultOcspUrl("http://ocsp.example.gov");

        // The issuer is known and the certificate itself is not to hand.
        RevocationChecker.Outcome withoutLeaf = checker(issuerDirectory()).check(certificate, null, NOW);
        assertThat(withoutLeaf.status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(withoutLeaf.detail())
                .contains("http://ocsp.example.gov")
                .contains("needs the certificate itself")
                .doesNotContain("names no CRL distribution point");

        // And the other way round: nothing to name the certificate by.
        RevocationChecker.Outcome withoutIssuer = checker(null).check(certificate, null, NOW);
        assertThat(withoutIssuer.status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(withoutIssuer.detail()).contains("needs the issuer's certificate");
    }

    /** With no responder to ask either, the certificate really does say nothing. */
    @Test
    void andWithNothingConfiguredItIsTheCertificateThatSaysNothing() {
        CachedCertificate certificate = parse(ca.issue(
                "silent", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null, null).der());

        RevocationChecker.Outcome outcome = checker(null).check(certificate, null, NOW);

        assertThat(outcome.status()).isEqualTo(RevocationStatus.UNKNOWN);
        assertThat(outcome.detail()).isEqualTo("The certificate names no CRL distribution point");
    }

    /**
     * The scheduled check stays on lists wherever there are lists. A responder answers
     * about one certificate and costs one request; a list answers about all of them and
     * costs one download, and on a directory of any size that is the whole difference.
     */
    @Test
    void aCertificateWithAListIsNotWorthFetchingTheCertificateFor() throws IOException {
        properties.setDefaultOcspUrl("http://ocsp.example.gov");
        RevocationChecker checker = checker(issuerDirectory());

        CachedCertificate withList = parse(ca.issue(
                "with-crldp", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)),
                publisher.url(CRL_PATH), null).der());
        CachedCertificate withNothing = parse(ca.issue(
                "no-crldp", NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)), null, null).der());

        assertThat(checker.onlyAResponderCanAnswer(withList)).isFalse();
        assertThat(checker.onlyAResponderCanAnswer(withNothing)).isTrue();

        // And a configured list does the same, since it answers for these too.
        properties.setDefaultCrlUrl(publisher.url(CRL_PATH));
        assertThat(checker(issuerDirectory()).onlyAResponderCanAnswer(withNothing)).isFalse();
    }

    // --- helpers ---------------------------------------------------------------------------

    private RevocationChecker checker(Path issuers) {
        properties.setIssuerDirectory(issuers == null ? null : issuers.toString());
        return new RevocationChecker(
                new CrlStore(properties), new IssuerCertificates(properties), properties);
    }

    private Path issuerDirectory() throws IOException {
        Path directory = Files.createTempDirectory("cert-alert-issuer");
        Files.writeString(directory.resolve("ca.pem"), ca.certificatePem());
        return directory;
    }

    /** A certificate the authority has revoked, with its list published where it says. */
    private CachedCertificate issueAndRevoke() {
        TestCa.Issued issued = ca.issue(
                "revoked",
                NOW.minus(Duration.ofDays(10)),
                NOW.plus(Duration.ofDays(355)),
                publisher.url(CRL_PATH),
                null);
        publisher.put(CRL_PATH, ca.crl(
                NOW.minus(Duration.ofHours(1)),
                NOW.plus(Duration.ofDays(1)),
                Map.of(issued.serial(), NOW.minus(Duration.ofDays(3)))));
        return parse(issued.der());
    }

    private CachedCertificate parse(byte[] der) {
        return parser.parse(der, NOW);
    }
}
